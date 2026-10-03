package dev.entity.client.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Uses vanilla item placement, including standing/wall variants and support checks. */
@Mixin(BlockItem.class)
public interface BlockItemPlacementInvoker {
    @Invoker(value="method_7707", remap=false)
    BlockState entity2$getPlacementState(ItemPlacementContext context);
}
