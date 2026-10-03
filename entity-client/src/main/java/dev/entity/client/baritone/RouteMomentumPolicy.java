package dev.entity.client.baritone;

/** Preserves sprint intent only across route segments whose geometry is already known safe. */
final class RouteMomentumPolicy {
    record Input(
            boolean routeActive,
            boolean leaseValid,
            boolean canSprint,
            boolean touchingWater,
            boolean inLava,
            boolean breakingBlock,
            boolean tacticalControllerActive,
            boolean horizontalCollision,
            double plannedVerticalChange) { }

    record Decision(boolean forceSprint, String reason) { }

    static Decision decide(Input input) {
        if (input == null || !input.routeActive() || !input.leaseValid()) {
            return new Decision(false, "no owned route");
        }
        if (!input.canSprint() || input.touchingWater() || input.inLava()
                || input.breakingBlock() || input.tacticalControllerActive()
                || input.horizontalCollision()
                || !Double.isFinite(input.plannedVerticalChange())) {
            return new Decision(false, "unsafe sprint context");
        }
        // Flat travel and a single-block descent are normal sprint geometry. Anything steeper or
        // ascending remains Baritone-owned so its jump/fall timing is not overridden.
        if (input.plannedVerticalChange() > 0.0 || input.plannedVerticalChange() < -1.0) {
            return new Decision(false, "route segment requires careful vertical movement");
        }
        return new Decision(true, input.plannedVerticalChange() < 0
                ? "committed safe one-block descent"
                : "committed flat sprint segment");
    }

    private RouteMomentumPolicy() { }
}
