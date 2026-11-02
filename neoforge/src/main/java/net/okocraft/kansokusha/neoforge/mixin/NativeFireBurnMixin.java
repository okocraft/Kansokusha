package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FireBlock.class)
abstract class NativeFireBurnMixin {
    @WrapMethod(method = "checkBurnOut")
    private void kansokusha$burn(Level level, BlockPos pos, int odds, RandomSource random,
                                 int age, Direction towardSource, Operation<Void> original) {
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null || !(level instanceof ServerLevel server)) {
            original.call(level, pos, odds, random, age, towardSource);
            return;
        }
        var before = server.getBlockState(pos);
        original.call(level, pos, odds, random, age, towardSource);
        var after = server.getBlockState(pos);
        if (!before.equals(after))
            recorder.recordFireChange(server, pos, before, after, pos.relative(towardSource), null);
    }
}
