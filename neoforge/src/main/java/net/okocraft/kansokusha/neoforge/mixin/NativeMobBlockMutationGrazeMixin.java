package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(EatBlockGoal.class)
abstract class NativeMobBlockMutationGrazeMixin {
    @Shadow @Final private Mob mob;

    @WrapMethod(method = "tick")
    private void kansokusha$graze(Operation<Void> original) {
        if (!(mob.level() instanceof ServerLevel level)) {
            original.call();
            return;
        }
        var scope = NativeBlockOperationCapture.entity(level, mob, "graze");
        try {
            original.call();
        } finally {
            NativeBlockOperationCapture.end(scope, true);
        }
    }
}
