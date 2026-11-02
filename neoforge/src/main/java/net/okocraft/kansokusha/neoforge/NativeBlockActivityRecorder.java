package net.okocraft.kansokusha.neoforge;

import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;

import java.util.ArrayList;
import java.util.List;

/** Targeted block operations; no global block-write interception on DQM's many dimensions. */
final class NativeBlockActivityRecorder {
    private final NativeEventRecorder recorder;
    private final List<Runnable> pending = new ArrayList<>();

    NativeBlockActivityRecorder(NativeEventRecorder recorder, KansokushaRuntime runtime) {
        this.recorder = recorder;
        NativeEventRecorder.register(runtime, "natural_block_change", "block_interaction");
        NeoForge.EVENT_BUS.addListener(this::cropGrowth);
        NeoForge.EVENT_BUS.addListener(this::interact);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void cropGrowth(CropGrowEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var before = event.getOriginalState();
        var after = event.getState();
        if (before.equals(after)) return;
        var payload = new JsonObject();
        payload.addProperty("cause", "crop_growth");
        payload.addProperty("before", before.toString());
        payload.addProperty("after", after.toString());
        recorder.record("natural_block_change", level, event.getPos(), null,
            blockKey(before.isAir() ? after : before), payload);
    }

    private void interact(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !(event.getLevel() instanceof ServerLevel level)) return;
        var pos = event.getPos().immutable();
        var state = level.getBlockState(pos);
        var block = state.getBlock();
        String operation = null;
        if (BuiltInRegistries.BLOCK.getKey(block).getPath().startsWith("suspicious_")
            && BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem()).getPath().equals("brush")) {
            operation = "brush";
        } else if (level.getBlockEntity(pos) instanceof net.minecraft.world.MenuProvider
            || level.getBlockEntity(pos) instanceof net.minecraft.world.Container
            || block instanceof SignBlock || block instanceof DoorBlock || block instanceof TrapDoorBlock
            || block instanceof FenceGateBlock || block instanceof ButtonBlock || block instanceof LeverBlock
            || block instanceof LecternBlock
            || BuiltInRegistries.BLOCK.getKey(block).getPath().equals("jukebox")
            || BuiltInRegistries.BLOCK.getKey(block).getPath().equals("chiseled_bookshelf")
            || BuiltInRegistries.BLOCK.getKey(block).getPath().equals("decorated_pot")) {
            operation = "interact";
        }
        if (operation == null) return;
        var payload = new JsonObject();
        payload.addProperty("operation", operation);
        payload.addProperty("hand", event.getHand().name());
        payload.addProperty("face", event.getFace() == null ? "unknown" : event.getFace().getName());
        if (!event.getItemStack().isEmpty())
            payload.addProperty("item", BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem()).toString());
        pending.add(() -> {
            if (event.isCanceled() || event.getUseBlock() == net.minecraft.util.TriState.FALSE
                && event.getUseItem() == net.minecraft.util.TriState.FALSE) return;
            recorder.record("block_interaction", level, pos, new PlayerActor(player.getUUID()), blockKey(state), payload);
        });
    }

    private void tick(ServerTickEvent.Post event) {
        for (var action : pending) action.run();
        pending.clear();
    }

    private static Key blockKey(BlockState state) {
        return Key.key(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}
