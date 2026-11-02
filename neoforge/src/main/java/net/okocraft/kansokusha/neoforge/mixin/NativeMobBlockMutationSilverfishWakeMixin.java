package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Silverfish;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.world.entity.monster.Silverfish$SilverfishWakeUpFriendsGoal")
abstract class NativeMobBlockMutationSilverfishWakeMixin {
    @Shadow @Final private Silverfish silverfish;

    @WrapMethod(method = "tick")
    private void kansokusha$release(Operation<Void> original) {
        if (!(silverfish.level() instanceof ServerLevel level)) {
            original.call();
            return;
        }
        var scope = NativeBlockOperationCapture.entity(level, silverfish, "silverfish_release");
        try {
            original.call();
        } finally {
            NativeBlockOperationCapture.end(scope, true);
        }
    }
}
