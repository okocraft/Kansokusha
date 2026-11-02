package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SnowGolem.class)
abstract class NativeMobBlockMutationSnowGolemMixin {
    @WrapMethod(method = "aiStep")
    private void kansokusha$snow(Operation<Void> original) {
        var golem = (SnowGolem) (Object) this;
        if (!(golem.level() instanceof ServerLevel level)) {
            original.call();
            return;
        }
        var scope = NativeBlockOperationCapture.entity(level, golem, "snow_golem_snow");
        try {
            original.call();
        } finally {
            NativeBlockOperationCapture.end(scope, true);
        }
    }
}
