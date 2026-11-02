package net.okocraft.kansokusha.neoforge.mixin;

import com.google.gson.JsonObject;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.kyori.adventure.key.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import net.okocraft.kansokusha.neoforge.NativeEventRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Records the accepted, filtered sign text after vanilla authorization and mutation. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class NativeSignUpdateMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "updateSignText")
    private void kansokusha$sign(ServerboundSignUpdatePacket packet, List<FilteredText> lines,
                                 Operation<Void> original) {
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null || !(player.level().getBlockEntity(packet.getPos()) instanceof SignBlockEntity beforeSign)) {
            original.call(packet, lines);
            return;
        }
        var before = text(beforeSign.getText(packet.isFrontText()));
        original.call(packet, lines);
        if (!(player.level().getBlockEntity(packet.getPos()) instanceof SignBlockEntity afterSign)) return;
        var after = text(afterSign.getText(packet.isFrontText()));
        if (before.equals(after)) return;
        var payload = new JsonObject();
        payload.addProperty("before", before);
        payload.addProperty("after", after);
        payload.addProperty("side", packet.isFrontText() ? "front" : "back");
        recorder.record("sign_change", player.level(), packet.getPos(), new PlayerActor(player.getUUID()),
            Key.key(BuiltInRegistries.BLOCK.getKey(afterSign.getBlockState().getBlock()).toString()), payload);
    }

    private static String text(SignText sign) {
        var result = new StringBuilder();
        for (int line = 0; line < 4; line++) {
            if (line != 0) result.append('\n');
            result.append(sign.getMessage(line, false).getString());
        }
        return result.toString();
    }
}
