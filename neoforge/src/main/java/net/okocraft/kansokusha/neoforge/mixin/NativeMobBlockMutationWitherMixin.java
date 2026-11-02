package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WitherBoss.class)
abstract class NativeMobBlockMutationWitherMixin {
    @WrapMethod(method = "customServerAiStep")
    private void kansokusha$breakBlocks(ServerLevel level, Operation<Void> original) {
        var scope = NativeBlockOperationCapture.entity(level, (WitherBoss) (Object) this, "wither_break");
        try {
            original.call(level);
        } finally {
            NativeBlockOperationCapture.end(scope, true);
        }
    }
}
