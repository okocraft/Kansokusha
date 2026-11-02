package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.dedicated.DedicatedServer;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DedicatedServer.class)
abstract class NativeWhitelistMixin {
    @WrapMethod(method = "setUsingWhitelist")
    private void kansokusha$toggle(boolean enabled, Operation<Void> original) {
        var server = (DedicatedServer) (Object) this;
        var before = server.isUsingWhitelist();
        original.call(enabled);
        var recorder = KansokushaNeoForge.recorder();
        var after = server.isUsingWhitelist();
        if (recorder != null && before != after) recorder.recordWhitelistToggle(before, after);
    }
}
