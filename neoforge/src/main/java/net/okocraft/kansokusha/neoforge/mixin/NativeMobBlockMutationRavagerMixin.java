package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Ravager;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Ravager.class)
abstract class NativeMobBlockMutationRavagerMixin {
    @WrapMethod(method = "aiStep")
    private void kansokusha$leaves(Operation<Void> original) {
        var ravager = (Ravager) (Object) this;
        if (!(ravager.level() instanceof ServerLevel level)) {
            original.call();
            return;
        }
        var scope = NativeBlockOperationCapture.entity(level, ravager, "ravager_leaves");
        try {
            original.call();
        } finally {
            NativeBlockOperationCapture.end(scope, true);
        }
    }
}
