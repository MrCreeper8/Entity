package dev.entity.client.mixin;

import dev.entity.client.baritone.NativeFailedAscendEdges;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Feedback belongs to one exact native instance, not Baritone's global settings. */
@Mixin(targets = "baritone.a", remap = false)
public abstract class BaritoneFailedAscendOwnerMixin implements NativeFailedAscendEdges.Owner {
    @Unique
    private final NativeFailedAscendEdges entity2$failedAscendEdges = new NativeFailedAscendEdges(this);

    @Override
    public NativeFailedAscendEdges entity2$failedAscendEdges() { return entity2$failedAscendEdges; }
}
