package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelData;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

/** Captures committed changes to the server's default spawn point. */
@Mixin(MinecraftServer.class)
abstract class NativeWorldSpawnMixin {
    @WrapMethod(method = "setRespawnData")
    private void kansokusha$spawnChange(LevelData.RespawnData requested, Operation<Void> original) {
        var server = (MinecraftServer) (Object) this;
        var before = server.getRespawnData();
        original.call(requested);
        var recorder = KansokushaNeoForge.recorder();
        if (recorder != null && !java.util.Objects.equals(before, server.getRespawnData()))
            recorder.recordWorldSpawn(server, before, server.getRespawnData());
    }
}
