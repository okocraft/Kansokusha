package net.okocraft.kansokusha.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.kyori.adventure.key.Key;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AnvilCraftEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Player activity with completed-event hooks or a tick-end state check for cancellable hooks. */
final class NativePlayerRecorder {
    private final NativeEventRecorder recorder;
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();

    NativePlayerRecorder(NativeEventRecorder recorder, KansokushaRuntime runtime) {
        this.recorder = recorder;
        NativeEventRecorder.register(runtime, "player_world_change", "player_teleport", "player_gamemode_change",
            "player_death", "player_spawn_change", "craft_item", "anvil_use", "smith_item", "enchant_item", "player_trade", "neoforge_chat",
            "neoforge_player_command", "neoforge_server_command");
        NeoForge.EVENT_BUS.addListener(this::worldChange);
        NeoForge.EVENT_BUS.addListener(this::gameMode);
        NeoForge.EVENT_BUS.addListener(this::death);
        NeoForge.EVENT_BUS.addListener(this::spawnPoint);
        NeoForge.EVENT_BUS.addListener(this::craft);
        NeoForge.EVENT_BUS.addListener(this::anvil);
        NeoForge.EVENT_BUS.addListener(this::enchant);
        NeoForge.EVENT_BUS.addListener(this::trade);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, this::chat);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, this::command);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void worldChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var payload = new JsonObject();
        payload.addProperty("from_world", event.getFrom().identifier().toString());
        payload.addProperty("to_world", event.getTo().identifier().toString());
        record("player_world_change", player, null, payload);
    }

    private void gameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var previous = event.getCurrentGameMode();
        pending.add(() -> {
            if (event.isCanceled() || player.gameMode.getGameModeForPlayer() == previous) return;
            var payload = new JsonObject();
            payload.addProperty("old_gamemode", previous.getName());
            payload.addProperty("new_gamemode", player.gameMode.getGameModeForPlayer().getName());
            record("player_gamemode_change", player, null, payload);
        });
    }

    private void death(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var level = (ServerLevel) player.level();
        var position = player.blockPosition().immutable();
        var payload = new JsonObject();
        payload.addProperty("cause", event.getSource().getMsgId());
        payload.add("storage", snapshotItems(player.getInventory().getNonEquipmentItems()));
        var armor = new JsonArray();
        for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
            armor.add(snapshotItem(player.getItemBySlot(slot)));
        payload.add("armor", armor);
        payload.add("offhand", snapshotItem(player.getItemBySlot(EquipmentSlot.OFFHAND)));
        var killer = event.getSource().getEntity();
        if (killer != null) payload.addProperty("killer", killer.getUUID().toString());
        pending.add(() -> {
            if (event.isCanceled() || !player.isDeadOrDying()) return;
            recorder.record("player_death", level, position, new PlayerActor(player.getUUID()), null, payload);
        });
    }

    private void spawnPoint(PlayerSetSpawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var before = player.getRespawnConfig();
        pending.add(() -> {
            var after = player.getRespawnConfig();
            if (event.isCanceled() || java.util.Objects.equals(before, after)) return;
            var payload = new JsonObject();
            payload.addProperty("before", before == null ? "default" : before.respawnData().globalPos().toString());
            payload.addProperty("after", after == null ? "default" : after.respawnData().globalPos().toString());
            record("player_spawn_change", player, null, payload);
        });
    }

    private void craft(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var item = event.getCrafting();
        var payload = new JsonObject();
        payload.addProperty("amount", item.getCount());
        record("craft_item", player, Key.key(BuiltInRegistries.ITEM.getKey(item.getItem()).toString()), payload);
    }

    private void anvil(AnvilCraftEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        recorder.recordAnvil(player, event.getLeft(), event.getRight(), event.getOutput());
    }

    private void enchant(PlayerEnchantItemEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var item = event.getEnchantedItem();
        var payload = new JsonObject();
        payload.addProperty("enchantment_count", event.getEnchantments().size());
        record("enchant_item", player, Key.key(BuiltInRegistries.ITEM.getKey(item.getItem()).toString()), payload);
    }

    private void trade(TradeWithVillagerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var result = event.getMerchantOffer().getResult();
        var payload = new JsonObject();
        payload.addProperty("amount", result.getCount());
        record("player_trade", player, Key.key(BuiltInRegistries.ITEM.getKey(result.getItem()).toString()), payload);
    }

    private void chat(ServerChatEvent event) {
        var player = event.getPlayer();
        var text = event.getRawText();
        var payload = new JsonObject();
        payload.addProperty("message", text);
        recorder.recordInput("neoforge_chat", Key.key(player.level().dimension().identifier().toString()),
            blockPosition(player.blockPosition()), player.getUUID(), java.time.Instant.now(), payload, text);
    }

    private void command(CommandEvent event) {
        var source = event.getParseResults().getContext().getSource();
        var command = event.getParseResults().getReader().getString();
        var payload = new JsonObject();
        payload.addProperty("command", command);
        if (source.getEntity() instanceof ServerPlayer player) {
            recorder.recordInput("neoforge_player_command", Key.key(player.level().dimension().identifier().toString()),
                blockPosition(player.blockPosition()), player.getUUID(), java.time.Instant.now(), payload, command);
        } else {
            var entity = source.getEntity();
            payload.addProperty("source_kind", entity != null ? "entity" : "server");
            payload.addProperty("source_name", source.getTextName());
            recorder.recordServerInput(entity == null ? null : new EntityActor(entity.getUUID(),
                Key.key(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())),
                java.time.Instant.now(), payload, command);
        }
    }

    private void tick(ServerTickEvent.Post event) {
        Runnable action;
        while ((action = pending.poll()) != null) action.run();
    }

    private void record(String name, ServerPlayer player, Key target, JsonObject payload) {
        recorder.record(name, (ServerLevel) player.level(), player.blockPosition(), new PlayerActor(player.getUUID()), target, payload);
    }

    private static net.okocraft.kansokusha.api.position.BlockPosition blockPosition(net.minecraft.core.BlockPos pos) {
        return new net.okocraft.kansokusha.api.position.BlockPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    private static JsonArray snapshotItems(List<ItemStack> stacks) {
        var items = new JsonArray();
        for (int slot = 0; slot < stacks.size(); slot++) {
            var stack = stacks.get(slot);
            if (stack.isEmpty()) continue;
            var entry = snapshotItem(stack);
            entry.addProperty("slot", slot);
            items.add(entry);
        }
        return items;
    }

    private static JsonObject snapshotItem(ItemStack stack) {
        var item = new JsonObject();
        if (!stack.isEmpty()) {
            item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            item.addProperty("amount", stack.getCount());
            item.addProperty("components", stack.getComponents().toString());
        }
        return item;
    }
}
