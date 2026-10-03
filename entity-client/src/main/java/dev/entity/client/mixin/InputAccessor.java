package dev.entity.client.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives the input-tick mixin atomic access to vanilla's derived vector. */
@Mixin(Input.class)
public interface InputAccessor {
    @Accessor("movementVector")
    void entity2$setMovementVector(Vec2f movementVector);
}
