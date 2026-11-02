package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerLevel.class)
abstract class NativeWeatherFormationMixin {
    @WrapMethod(method = "tickPrecipitation")
    private void kansokusha$precipitation(BlockPos position, Operation<Void> original) {
        var scope = NativeBlockOperationCapture.natural((ServerLevel) (Object) this, "weather_formation");
        boolean completed = false;
        try {
            original.call(position);
            completed = true;
        } finally {
            NativeBlockOperationCapture.end(scope, completed);
        }
    }
}
