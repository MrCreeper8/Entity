package dev.entity.client.mixin;

import dev.entity.client.baritone.ResourceDescentSafetyPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Route policy is local to the actual native instance and visible to A* workers. */
@Mixin(targets = "baritone.a", remap = false)
public abstract class BaritoneResourceDescentOwnerMixin implements ResourceDescentSafetyPolicy.NativeOwner {
    @Unique private volatile boolean entity2$resourceDescentRequired;

    @Override public boolean entity2$resourceDescentRequired() { return entity2$resourceDescentRequired; }
    @Override public void entity2$resourceDescentRequired(boolean required) {
        entity2$resourceDescentRequired = required;
    }
}
