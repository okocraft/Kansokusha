package net.okocraft.kansokusha.neoforge.mixin;

import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemFrame.class)
public interface NativeItemFrameAccess {
    @Accessor("fixed")
    boolean kansokusha$isFixed();
}
