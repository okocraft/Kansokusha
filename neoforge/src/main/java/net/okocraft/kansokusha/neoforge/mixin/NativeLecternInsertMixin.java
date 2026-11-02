package net.okocraft.kansokusha.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LecternBlock.class)
abstract class NativeLecternInsertMixin {
    @WrapMethod(method = "tryPlaceBook")
    private static boolean kansokusha$insert(LivingEntity entity, Level level, BlockPos pos,
                                              BlockState state, ItemStack book, Operation<Boolean> original) {
        var snapshot = book.copy();
        var placed = original.call(entity, level, pos, state, book);
        var recorder = KansokushaNeoForge.recorder();
        if (placed && recorder != null && entity instanceof ServerPlayer player
            && level instanceof ServerLevel server
            && server.getBlockEntity(pos) instanceof LecternBlockEntity lectern
            && !lectern.getBook().isEmpty())
            recorder.recordLecternInsert(server, pos, player, snapshot);
        return placed;
    }
}
