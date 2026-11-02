package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(UserWhiteList.class)
abstract class NativeWhitelistProfileMixin {
    @WrapMethod(method = "add(Lnet/minecraft/server/players/UserWhiteListEntry;)Z")
    private boolean kansokusha$add(UserWhiteListEntry entry, Operation<Boolean> original) {
        var added = original.call(entry);
        var recorder = KansokushaNeoForge.recorder();
        if (added && recorder != null) recorder.recordWhitelistProfile("add", entry.getUser());
        return added;
    }

    @WrapMethod(method = "remove(Lnet/minecraft/server/players/NameAndId;)Z")
    private boolean kansokusha$remove(NameAndId profile, Operation<Boolean> original) {
        var removed = original.call(profile);
        var recorder = KansokushaNeoForge.recorder();
        if (removed && recorder != null) recorder.recordWhitelistProfile("remove", profile);
        return removed;
    }
}
