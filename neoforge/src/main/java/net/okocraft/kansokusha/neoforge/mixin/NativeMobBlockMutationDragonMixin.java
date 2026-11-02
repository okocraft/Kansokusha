package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.AABB;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EnderDragon.class)
abstract class NativeMobBlockMutationDragonMixin {
    @WrapMethod(method = "checkWalls")
    private boolean kansokusha$dragonBreak(ServerLevel level, AABB bounds, Operation<Boolean> original) {
        var scope = NativeBlockOperationCapture.dragon(level, (EnderDragon) (Object) this, "dragon_impact");
        try {
            return original.call(level, bounds);
        } finally {
            NativeBlockOperationCapture.end(scope, true);
        }
    }
}
