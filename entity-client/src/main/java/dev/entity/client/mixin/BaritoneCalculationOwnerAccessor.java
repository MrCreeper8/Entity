package dev.entity.client.mixin;

import baritone.api.IBaritone;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ProGuard ca has several fields named a; the return descriptor selects IBaritone exactly. */
@Mixin(targets = "baritone.ca", remap = false)
public interface BaritoneCalculationOwnerAccessor {
    @Accessor(value = "a", remap = false)
    IBaritone entity2$calculationOwner();
}
