package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.LecternMenu;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LecternMenu.class)
abstract class NativeLecternTakeMixin {
    @WrapMethod(method = "clickMenuButton")
    private boolean kansokusha$take(Player player, int button, Operation<Boolean> original) {
        var menu = (LecternMenu) (Object) this;
        var before = button == LecternMenu.BUTTON_TAKE_BOOK ? menu.getBook().copy()
            : net.minecraft.world.item.ItemStack.EMPTY;
        var accepted = original.call(player, button);
        var recorder = KansokushaNeoForge.recorder();
        if (button == LecternMenu.BUTTON_TAKE_BOOK && accepted && !before.isEmpty()
            && menu.getBook().isEmpty() && player instanceof ServerPlayer actor && recorder != null)
            recorder.recordLecternTake(actor, before);
        return accepted;
    }
}
