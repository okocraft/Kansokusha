package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.portal.PortalShape;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PortalShape.class)
abstract class NativePortalCreationMixin {
    @WrapMethod(method = "createPortalBlocks")
    private void kansokusha$portal(LevelAccessor level, Operation<Void> original) {
        var scope = level instanceof ServerLevel server
            ? NativeBlockOperationCapture.natural(server, "portal_creation") : null;
        boolean completed = false;
        try {
            original.call(level);
            completed = true;
        } finally {
            NativeBlockOperationCapture.end(scope, completed);
        }
    }
}
