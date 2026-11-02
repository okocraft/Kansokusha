package net.okocraft.kansokusha.neoforge.mixin;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RandomStrollGoal.class)
public interface NativeMobBlockMutationStrollAccess {
    @Accessor("mob")
    PathfinderMob kansokusha$mob();
}
