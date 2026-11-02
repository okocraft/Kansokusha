package net.okocraft.kansokusha.neoforge.mixin;

import com.google.gson.JsonObject;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.neoforge.KansokushaNeoForge;
import net.okocraft.kansokusha.neoforge.NativeEventRecorder;
import org.spongepowered.asm.mixin.Mixin;

/** Records successful native bucket mutations at their actual block coordinate. */
@Mixin(BucketItem.class)
abstract class NativeBucketMixin {
    @WrapMethod(method = "use")
    private InteractionResult kansokusha$bucket(Level level, Player player, InteractionHand hand,
                                                 Operation<InteractionResult> original) {
        var recorder = KansokushaNeoForge.recorder();
        if (recorder == null || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return original.call(level, player, hand);
        }
        var bucket = (BucketItem) (Object) this;
        BlockHitResult hit = Item.getPlayerPOVHitResult(level, player, bucket.getFluidContext());
        if (hit.getType() != HitResult.Type.BLOCK) return original.call(level, player, hand);
        BlockPos clicked = hit.getBlockPos();
        BlockPos adjacent = clicked.relative(hit.getDirection());
        BlockState clickedBefore = level.getBlockState(clicked);
        BlockState adjacentBefore = level.getBlockState(adjacent);
        var held = player.getItemInHand(hand).copy();
        var result = original.call(level, player, hand);
        var name = bucket.getFluidContext() == ClipContext.Fluid.SOURCE_ONLY ? "bucket_fill" : "bucket_empty";
        record(recorder, serverPlayer, serverLevel, name, clicked, clickedBefore, level.getBlockState(clicked), held);
        record(recorder, serverPlayer, serverLevel, name, adjacent, adjacentBefore, level.getBlockState(adjacent), held);
        return result;
    }

    private static void record(NativeEventRecorder recorder, ServerPlayer player, ServerLevel level, String name,
                               BlockPos position, BlockState before, BlockState after, net.minecraft.world.item.ItemStack held) {
        if (before.equals(after)) return;
        var payload = new JsonObject();
        payload.addProperty("before", before.toString());
        payload.addProperty("after", after.toString());
        payload.addProperty("bucket", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        var target = before.isAir() ? after : before;
        recorder.record(name, level, position, new PlayerActor(player.getUUID()),
            Key.key(BuiltInRegistries.BLOCK.getKey(target.getBlock()).toString()), payload);
    }
}
