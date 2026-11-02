package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.world.entity.monster.Silverfish$SilverfishMergeWithStoneGoal")
abstract class NativeMobBlockMutationSilverfishMergeMixin {
    @WrapMethod(method = "start")
    private void kansokusha$merge(Operation<Void> original) {
        var mob = ((NativeMobBlockMutationStrollAccess) this).kansokusha$mob();
        if (!(mob.level() instanceof ServerLevel level)) {
            original.call();
            return;
        }
        var scope = NativeBlockOperationCapture.entity(level, mob, "silverfish_merge");
        try {
            original.call();
        } finally {
            NativeBlockOperationCapture.end(scope, true);
        }
    }
}
