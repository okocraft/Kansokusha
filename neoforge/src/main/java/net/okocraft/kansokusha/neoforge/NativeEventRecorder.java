package net.okocraft.kansokusha.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.neoforge.capture.NativeBlockOperationCapture;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Native events are written with payload generation 2; Paper's generation 1 uses a different binary codec. */
public final class NativeEventRecorder {
    private static final PayloadGeneration NATIVE_PAYLOAD = new PayloadGeneration(2);
    private final KansokushaRuntime runtime;
    private final Key serverKey;
    private final List<ItemTossEvent> pendingTosses = new ArrayList<>();
    private List<ItemTossEvent> activeClickTosses;
    private final Map<UUID, BlockPos> interactedBlocks = new HashMap<>();
    private final Map<UUID, OpenContainer> openContainers = new HashMap<>();
    private final Map<UUID, OpenContainer> workstationOrigins = new HashMap<>();
    private final NativePistonRecorder pistons;
    private final NativeContainerMoveRecorder containerMoves;

    NativeEventRecorder(KansokushaRuntime runtime, Key serverKey) {
        this.runtime = runtime;
        this.serverKey = serverKey;
        for (var name : List.of("block_break", "block_place", "sign_change", "bucket_empty", "bucket_fill",
            "fluid_change", "explosion_block_change", "block_fertilize", "piston_move",
            "flower_pot_change", "tnt_prime", "block_ignite", "block_burn", "sponge_absorb",
            "cauldron_level_change", "entity_block_change", "block_harvest",
            "lectern_change",
            "container_transaction", "container_transfer", "container_pickup", "container_process", "dispenser_dispense",
            "item_drop", "item_pickup",
            "book_edit",
            "neoforge_join", "neoforge_quit", "neoforge_kick")) {
            runtime.registerEventType(new EventTypeDefinition(type(name), NATIVE_PAYLOAD));
        }
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::blockBreak);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::blockPlace);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::fluidChange);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::explosion);
        NeoForge.EVENT_BUS.addListener(this::interactBlock);
        NeoForge.EVENT_BUS.addListener(this::containerOpen);
        NeoForge.EVENT_BUS.addListener(this::containerClose);
        NeoForge.EVENT_BUS.addListener(this::itemToss);
        NeoForge.EVENT_BUS.addListener(this::itemPickup);
        NeoForge.EVENT_BUS.addListener(this::join);
        NeoForge.EVENT_BUS.addListener(this::quit);
        NeoForge.EVENT_BUS.addListener(this::tick);
        new NativePlayerRecorder(this, runtime);
        new NativeWorldRecorder(this, runtime);
        new NativeEntityRecorder(this, runtime);
        new NativeBlockActivityRecorder(this, runtime);
        pistons = new NativePistonRecorder(this);
        containerMoves = new NativeContainerMoveRecorder(this);
    }

    private void blockBreak(BreakBlockEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)
            || !(event.getLevel() instanceof ServerLevel level)) return;
        var payload = new JsonObject();
        payload.addProperty("before", event.getState().toString());
        payload.addProperty("after", "minecraft:air");
        payload.addProperty("boundary", "accepted_attempt");
        record("block_break", level, event.getPos(), new PlayerActor(player.getUUID()),
            blockKey(event.getState()), payload);
    }

    private void blockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        var entity = event.getEntity();
        if (entity == null) return;
        if ((entity instanceof Player && NativeBlockOperationCapture.fertilizing(level))
            || (!(entity instanceof Player) && NativeBlockOperationCapture.ownsEntityMutation(level, entity))) return;
        EventActor actor = entity instanceof Player player ? new PlayerActor(player.getUUID())
            : new EntityActor(entity.getUUID(),
                Key.key(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
        var eventType = entity instanceof Player ? "block_place" : "entity_block_change";
        var snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent multi
            ? multi.getReplacedBlockSnapshots() : List.of(event.getBlockSnapshot());
        var occurredAt = Instant.now();
        for (var snapshot : snapshots) {
            var before = snapshot.getState();
            var after = level.getBlockState(snapshot.getPos());
            var payload = new JsonObject();
            payload.addProperty("before", before.toString());
            payload.addProperty("after", after.toString());
            payload.addProperty("boundary", "accepted_attempt");
            recordAt(eventType, level, snapshot.getPos(), actor,
                blockKey(eventType.equals("block_place") ? after : before.isAir() ? after : before), payload, occurredAt);
        }
    }

    private void fluidChange(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        var before = event.getOriginalState();
        var after = event.getNewState();
        if (before.equals(after)) return;
        var fluid = after.getFluidState();
        if (fluid.isEmpty()) {
            var source = level.getFluidState(event.getLiquidPos());
            if (source.is(net.minecraft.tags.FluidTags.WATER)
                || source.is(net.minecraft.tags.FluidTags.LAVA))
                recordNaturalChange(level, event.getPos(), before, after, "fluid_form", event.getLiquidPos());
            return;
        }
        if (!(fluid.is(net.minecraft.tags.FluidTags.WATER)
            || fluid.is(net.minecraft.tags.FluidTags.LAVA))) return;
        var payload = new JsonObject();
        payload.addProperty("before", before.toString());
        payload.addProperty("after", after.toString());
        payload.addProperty("source", event.getLiquidPos().toShortString());
        payload.addProperty("boundary", "accepted_attempt");
        record("fluid_change", level, event.getPos(), null, blockKey(before.isAir() ? after : before), payload);
    }

    private void explosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var direct = event.getExplosion().getDirectSourceEntity();
        EventActor actor = direct instanceof Player player ? new PlayerActor(player.getUUID())
            : direct == null ? null : new EntityActor(direct.getUUID(),
                Key.key(BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()).toString()));
        var occurredAt = Instant.now();
        for (var position : event.getAffectedBlocks()) {
            var before = level.getBlockState(position);
            if (before.isAir()) continue;
            if (before.is(net.minecraft.world.level.block.Blocks.TNT)
                && level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.TNT_EXPLODES)
                && !(direct instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)) continue;
            var payload = new JsonObject();
            payload.addProperty("before", before.toString());
            payload.addProperty("after", "minecraft:air");
            payload.addProperty("boundary", "accepted_attempt");
            recordAt("explosion_block_change", level, position, actor, blockKey(before), payload, occurredAt);
        }
    }

    private void tick(ServerTickEvent.Post event) {
        for (var toss : pendingTosses) {
            if (toss.isCanceled() || !(toss.getPlayer() instanceof ServerPlayer player)) continue;
            var stack = toss.getEntity().getItem();
            var payload = new JsonObject();
            payload.addProperty("amount", stack.getCount());
            record("item_drop", (ServerLevel) player.level(), player.blockPosition(), new PlayerActor(player.getUUID()), itemKey(stack), payload);
        }
        pendingTosses.clear();
        interactedBlocks.clear();
    }

    private void interactBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && !event.isCanceled()) {
            interactedBlocks.put(player.getUUID(), event.getPos().immutable());
        }
    }

    private void containerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var position = interactedBlocks.remove(player.getUUID());
        if ((event.getContainer() instanceof AnvilMenu || event.getContainer() instanceof LecternMenu)
            && position != null) {
            workstationOrigins.put(player.getUUID(), new OpenContainer((ServerLevel) player.level(), position));
        }
        if (!isStorageMenu(event.getContainer())) return;
        var dqmOrigin = dqmOrigin(event.getContainer());
        if (dqmOrigin != null) {
            if (dqmOrigin.level != null && dqmOrigin.position != null)
                openContainers.put(player.getUUID(), dqmOrigin);
            return;
        }
        for (Slot slot : event.getContainer().slots) {
            if (slot.container instanceof BlockEntity block && block.getLevel() instanceof ServerLevel level) {
                openContainers.put(player.getUUID(), new OpenContainer(level, block.getBlockPos()));
                return;
            }
        }
        if (position != null) openContainers.put(player.getUUID(), new OpenContainer((ServerLevel) player.level(), position));
    }

    private static OpenContainer dqmOrigin(AbstractContainerMenu menu) {
        try {
            var originType = Class.forName("org.popcraft.bolt.neoforge.compat.dqm.DqmMenuOrigin", false,
                menu.getClass().getClassLoader());
            if (!originType.isInstance(menu)) return null;
            return new OpenContainer((ServerLevel) originType.getMethod("bolt$level").invoke(menu),
                (BlockPos) originType.getMethod("bolt$pos").invoke(menu));
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot resolve DQM menu origin", failure);
        }
    }

    private void containerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        openContainers.remove(player.getUUID());
        workstationOrigins.remove(player.getUUID());
    }

    public void withContainerClick(ServerPlayer player, ServerboundContainerClickPacket packet, Runnable operation) {
        var origin = openContainers.get(player.getUUID());
        var menu = player.containerMenu;
        if (origin == null || !isStorageMenu(menu)) {
            operation.run();
            return;
        }
        var before = containerStacks(menu, player);
        var beforePlayer = playerStacks(menu, player);
        var tosses = new ArrayList<ItemTossEvent>();
        var previousTosses = activeClickTosses;
        activeClickTosses = tosses;
        try {
            operation.run();
        } finally {
            activeClickTosses = previousTosses;
        }
        if (menu != player.containerMenu) return;
        var after = containerStacks(menu, player);
        var afterPlayer = playerStacks(menu, player);
        if (packet.containerInput() == ContainerInput.THROW && packet.slotNum() >= 0
            && packet.slotNum() < menu.slots.size()
            && menu.slots.get(packet.slotNum()).container != player.getInventory()) {
            for (var toss : tosses) {
                if (!toss.isCanceled() && toss.getPlayer() == player)
                    afterPlayer.add(toss.getEntity().getItem().copy());
            }
        }
        for (var delta : NativeInventoryDeltas.transfers(before, after, beforePlayer, afterPlayer)) {
            var payload = new JsonObject();
            payload.addProperty("amount_delta", delta.amount());
            payload.addProperty("direction", delta.amount() > 0 ? "added_to_container" : "removed_from_container");
            payload.addProperty("components", delta.item().getComponents().toString());
            record("container_transaction", origin.level, origin.position,
                new PlayerActor(player.getUUID()), itemKey(delta.item()), payload);
        }
    }

    public void recordInventoryDiff(String eventType, ServerLevel level, BlockPos position, EventActor actor,
                             List<ItemStack> before, List<ItemStack> after, boolean removalsOnly) {
        var deltas = new ArrayList<ItemDelta>();
        for (var stack : before) merge(deltas, stack, -1);
        for (var stack : after) merge(deltas, stack, 1);
        for (var delta : deltas) {
            if (delta.amount == 0 || (removalsOnly && delta.amount > 0)) continue;
            var payload = new JsonObject();
            payload.addProperty("amount_delta", delta.amount);
            payload.addProperty("direction", delta.amount > 0 ? "added_to_container" : "removed_from_container");
            payload.addProperty("components", delta.template.getComponents().toString());
            record(eventType, level, position, actor,
                itemKey(delta.template), payload);
        }
    }

    private static boolean isStorageMenu(AbstractContainerMenu menu) {
        return !(menu instanceof CraftingMenu || menu instanceof AnvilMenu || menu instanceof SmithingMenu
            || menu instanceof GrindstoneMenu || menu instanceof EnchantmentMenu || menu instanceof StonecutterMenu
            || menu instanceof CartographyTableMenu || menu instanceof LoomMenu || menu instanceof MerchantMenu);
    }

    private static List<ItemStack> containerStacks(AbstractContainerMenu menu, Player player) {
        var result = new ArrayList<ItemStack>();
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            result.add(slot.getItem().copy());
        }
        return result;
    }

    private static List<ItemStack> playerStacks(AbstractContainerMenu menu, ServerPlayer player) {
        var result = new ArrayList<ItemStack>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) result.add(inventory.getItem(slot).copy());
        result.add(menu.getCarried().copy());
        return result;
    }

    private static void merge(List<ItemDelta> deltas, ItemStack stack, int sign) {
        if (stack.isEmpty()) return;
        for (var entry : deltas) {
            if (ItemStack.isSameItemSameComponents(entry.template, stack)) {
                entry.amount += sign * stack.getCount();
                return;
            }
        }
        deltas.add(new ItemDelta(stack.copyWithCount(1), sign * stack.getCount()));
    }

    private void itemToss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer) {
            pendingTosses.add(event);
            if (activeClickTosses != null) activeClickTosses.add(event);
        }
    }

    private void itemPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        var original = event.getOriginalStack();
        var amount = original.getCount() - event.getCurrentStack().getCount();
        if (amount <= 0) return;
        var payload = new JsonObject();
        payload.addProperty("amount", amount);
        record("item_pickup", (ServerLevel) player.level(), player.blockPosition(), new PlayerActor(player.getUUID()), itemKey(original), payload);
    }

    private void join(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var payload = new JsonObject();
        payload.addProperty("username", player.getGameProfile().name());
        runtime.submitPlayerLogin(submission("neoforge_join", (ServerLevel) player.level(), player.blockPosition(),
            new PlayerActor(player.getUUID()), null, payload), player.getGameProfile().name());
    }

    private void quit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        openContainers.remove(player.getUUID());
        workstationOrigins.remove(player.getUUID());
        interactedBlocks.remove(player.getUUID());
        record("neoforge_quit", (ServerLevel) player.level(), player.blockPosition(), new PlayerActor(player.getUUID()),
            null, new JsonObject());
    }

    public void record(String eventType, ServerLevel level, BlockPos position, EventActor actor, Key target,
                        JsonObject payload) {
        runtime.submit(submission(eventType, level, position, actor, target, payload));
    }

    void recordAt(String eventType, ServerLevel level, BlockPos position, EventActor actor, Key target,
                  JsonObject payload, Instant occurredAt) {
        runtime.submit(new EventSubmission(type(eventType), NATIVE_PAYLOAD, occurredAt, serverKey,
            Key.key(level.dimension().identifier().toString()),
            new BlockPosition(position.getX(), position.getY(), position.getZ()), actor, target,
            EventPayload.copyOf(payload.toString().getBytes(StandardCharsets.UTF_8))));
    }

    void flush() {
        pistons.flushAll();
        containerMoves.flushAll();
    }

    public void recordWorldSpawn(MinecraftServer server, LevelData.RespawnData before, LevelData.RespawnData after) {
        var level = server.getLevel(after.dimension());
        if (level == null) return;
        var payload = new JsonObject();
        payload.addProperty("before", before == null ? "unknown" : before.globalPos().toString());
        payload.addProperty("after", after.globalPos().toString());
        record("world_spawn_change", level, after.pos(), null, null, payload);
    }

    public void recordFurnaceProcess(ServerLevel level, BlockPos position, ItemStack input,
                                     ItemStack output, int amount) {
        var payload = new JsonObject();
        payload.addProperty("process_kind", "smelt");
        payload.addProperty("amount", amount);
        if (!input.isEmpty()) payload.addProperty("input_item", itemKey(input).asString());
        record("container_process", level, position,
            new BlockActor(blockKey(level.getBlockState(position))), itemKey(output), payload);
    }

    public void recordBrewProcess(ServerLevel level, BlockPos position, ItemStack input,
                                  ItemStack ingredient, ItemStack result) {
        var payload = new JsonObject();
        payload.addProperty("process_kind", "brew");
        payload.add("input", itemContents(input));
        payload.add("ingredient", itemContents(ingredient));
        payload.add("result", itemContents(result));
        record("container_process", level, position,
            new BlockActor(blockKey(level.getBlockState(position))),
            ingredient.isEmpty() ? null : itemKey(ingredient), payload);
    }

    public void recordCookProcess(ServerLevel level, BlockPos position, ItemStack input, ItemStack result) {
        var payload = new JsonObject();
        payload.addProperty("process_kind", "campfire_cook");
        payload.add("input", itemContents(input));
        payload.add("result", itemContents(result));
        record("container_process", level, position,
            new BlockActor(blockKey(level.getBlockState(position))), itemKey(result), payload);
    }

    public void recordCrafterProcess(ServerLevel level, BlockPos position,
                                     net.minecraft.world.level.block.entity.CrafterBlockEntity crafter,
                                     ItemStack result) {
        var payload = new JsonObject();
        payload.addProperty("process_kind", "crafter_craft");
        var inputs = new JsonArray();
        for (int slot = 0; slot < crafter.getContainerSize(); slot++) inputs.add(itemContents(crafter.getItem(slot)));
        payload.add("inputs", inputs);
        payload.add("result", itemContents(result));
        record("container_process", level, position,
            new BlockActor(blockKey(level.getBlockState(position))), itemKey(result), payload);
    }

    public void recordSmith(ServerPlayer player, ItemStack result) {
        var payload = new JsonObject();
        payload.addProperty("amount", result.getCount());
        record("smith_item", (ServerLevel) player.level(), player.blockPosition(),
            new PlayerActor(player.getUUID()), itemKey(result), payload);
    }

    void recordAnvil(ServerPlayer player, ItemStack left, ItemStack right, ItemStack output) {
        var origin = workstationOrigins.get(player.getUUID());
        if (origin == null || output.isEmpty() || left.isEmpty()) return;
        var payload = new JsonObject();
        payload.add("left", itemContents(left));
        payload.add("right", itemContents(right));
        payload.add("result", itemContents(output));
        var customName = output.get(DataComponents.CUSTOM_NAME);
        if (customName != null) payload.addProperty("rename_text", customName.getString());
        record("anvil_use", origin.level, origin.position, new PlayerActor(player.getUUID()), itemKey(output), payload);
    }

    private static JsonObject itemContents(ItemStack stack) {
        var result = new JsonObject();
        if (!stack.isEmpty()) {
            result.addProperty("item", itemKey(stack).asString());
            result.addProperty("amount", stack.getCount());
            result.addProperty("components", stack.getComponents().toString());
        }
        return result;
    }

    public void recordTeleport(ServerPlayer player, Key fromWorld, Vec3 from) {
        var toWorld = Key.key(player.level().dimension().identifier().toString());
        var to = player.position();
        if (fromWorld.equals(toWorld) && from.equals(to)) return;
        var payload = new JsonObject();
        payload.addProperty("from_world", fromWorld.asString());
        payload.addProperty("from", from.toString());
        payload.addProperty("to", to.toString());
        record("player_teleport", (ServerLevel) player.level(), player.blockPosition(),
            new PlayerActor(player.getUUID()), null, payload);
    }

    void recordInput(String eventType, Key world, BlockPosition position, UUID playerId,
                     Instant occurredAt, JsonObject payload, String searchText) {
        runtime.submitSearchable(new EventSubmission(type(eventType), NATIVE_PAYLOAD, occurredAt, serverKey,
            world, position, new PlayerActor(playerId), null,
            EventPayload.copyOf(payload.toString().getBytes(StandardCharsets.UTF_8))), searchText);
    }

    void recordServerInput(EventActor actor, Instant occurredAt, JsonObject payload, String searchText) {
        runtime.submitSearchable(new EventSubmission(type("neoforge_server_command"), NATIVE_PAYLOAD,
            occurredAt, serverKey, null, null, actor, null,
            EventPayload.copyOf(payload.toString().getBytes(StandardCharsets.UTF_8))), searchText);
    }

    public void recordBookEdit(ServerPlayer player, ItemStack before, ItemStack after) {
        if (after.isEmpty()) return;
        var payload = new JsonObject();
        payload.add("before", bookContents(before));
        payload.add("after", bookContents(after));
        payload.addProperty("signing", after.has(DataComponents.WRITTEN_BOOK_CONTENT));
        record("book_edit", (ServerLevel) player.level(), player.blockPosition(),
            new PlayerActor(player.getUUID()), itemKey(after), payload);
    }

    private static JsonObject bookContents(ItemStack stack) {
        var result = new JsonObject();
        if (!stack.isEmpty()) result.addProperty("item", itemKey(stack).asString());
        var pages = new JsonArray();
        var writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (writable != null) writable.getPages(false).forEach(pages::add);
        var written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            result.addProperty("title", written.title().raw());
            result.addProperty("author", written.author());
            written.getPages(false).stream().map(net.minecraft.network.chat.Component::getString).forEach(pages::add);
        }
        result.add("pages", pages);
        return result;
    }

    void recordGlobal(String eventType, JsonObject payload) {
        runtime.submit(new EventSubmission(type(eventType), NATIVE_PAYLOAD, Instant.now(), serverKey,
            null, null, null, null, EventPayload.copyOf(payload.toString().getBytes(StandardCharsets.UTF_8))));
    }

    public void recordWhitelistToggle(boolean before, boolean after) {
        var payload = new JsonObject();
        payload.addProperty("action", "toggle");
        payload.addProperty("before", before);
        payload.addProperty("after", after);
        recordGlobal("whitelist_change", payload);
    }

    public void recordWhitelistProfile(String action, net.minecraft.server.players.NameAndId profile) {
        var payload = new JsonObject();
        payload.addProperty("action", action);
        payload.addProperty("profile_id", profile.id().toString());
        payload.addProperty("profile_name", profile.name());
        recordGlobal("whitelist_change", payload);
    }

    public void recordFlowerPot(ServerLevel level, BlockPos position, ServerPlayer player,
                                net.minecraft.world.level.block.FlowerPotBlock before,
                                net.minecraft.world.level.block.FlowerPotBlock after,
                                ItemStack heldItem) {
        var input = before.getPotted();
        var output = after.getPotted();
        if (input == output) return;
        var changed = output == net.minecraft.world.level.block.Blocks.AIR ? input : output;
        var payload = new JsonObject();
        payload.addProperty("action", output == net.minecraft.world.level.block.Blocks.AIR ? "remove" : "insert");
        payload.addProperty("before", BuiltInRegistries.BLOCK.getKey(input).toString());
        payload.addProperty("after", BuiltInRegistries.BLOCK.getKey(output).toString());
        if (!heldItem.isEmpty()) payload.addProperty("held_item", itemKey(heldItem).asString());
        record("flower_pot_change", level, position, new PlayerActor(player.getUUID()),
            Key.key(BuiltInRegistries.ITEM.getKey(changed.asItem()).toString()), payload);
    }

    public void recordTntPrime(ServerLevel level, BlockPos position, Entity igniter) {
        var payload = new JsonObject();
        payload.addProperty("primed", true);
        EventActor actor = igniter instanceof Player player ? new PlayerActor(player.getUUID())
            : igniter == null ? null : new EntityActor(igniter.getUUID(),
                Key.key(BuiltInRegistries.ENTITY_TYPE.getKey(igniter.getType()).toString()));
        record("tnt_prime", level, position, actor, Key.key("minecraft:tnt"), payload);
    }

    public void recordLecternInsert(ServerLevel level, BlockPos position, ServerPlayer player, ItemStack book) {
        recordLecternChange(level, position, player, book, "insert");
    }

    public void recordLecternTake(ServerPlayer player, ItemStack book) {
        var origin = workstationOrigins.get(player.getUUID());
        if (origin != null) recordLecternChange(origin.level, origin.position, player, book, "take");
    }

    public void recordFireChange(ServerLevel level, BlockPos position, BlockState before,
                                 BlockState after, BlockPos source, Entity igniter) {
        if (before.equals(after)) return;
        EventActor actor = igniter instanceof Player player ? new PlayerActor(player.getUUID())
            : igniter == null ? null : new EntityActor(igniter.getUUID(),
                Key.key(BuiltInRegistries.ENTITY_TYPE.getKey(igniter.getType()).toString()));
        if (actor == null && source != null)
            actor = new BlockActor(blockKey(level.getBlockState(source)));
        if (!before.isAir() && before.getBlock() != net.minecraft.world.level.block.Blocks.FIRE
            && before.getBlock() != net.minecraft.world.level.block.Blocks.SOUL_FIRE
            && (before.getBlock() != net.minecraft.world.level.block.Blocks.TNT
                || !level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.TNT_EXPLODES))) {
            var burn = new JsonObject();
            burn.addProperty("before", before.toString());
            burn.addProperty("after", after.toString());
            burn.addProperty("cause", "fire");
            record("block_burn", level, position, actor, blockKey(before), burn);
        }
        if (after.getBlock() == net.minecraft.world.level.block.Blocks.FIRE
            || after.getBlock() == net.minecraft.world.level.block.Blocks.SOUL_FIRE) {
            if (igniter == null && source != null) {
                recordNaturalChange(level, position, before, after, "fire_spread", source);
                return;
            }
            var ignite = new JsonObject();
            ignite.addProperty("before", before.toString());
            ignite.addProperty("after", after.toString());
            ignite.addProperty("cause", igniter == null ? "fire_spread" : "entity_ignite");
            if (source != null) ignite.addProperty("source", source.toShortString());
            record("block_ignite", level, position, actor, blockKey(after), ignite);
        }
    }

    public void recordNaturalChange(ServerLevel level, BlockPos position, BlockState before,
                                    BlockState after, String cause, BlockPos source) {
        if (before.equals(after)) return;
        var payload = new JsonObject();
        payload.addProperty("before", before.toString());
        payload.addProperty("after", after.toString());
        payload.addProperty("cause", cause);
        if (source != null) payload.addProperty("source", source.toShortString());
        record("natural_block_change", level, position,
            source == null ? null : new BlockActor(blockKey(level.getBlockState(source))),
            blockKey(before.isAir() ? after : before), payload);
    }

    public void recordScopedBlockChange(String eventType, ServerLevel level, BlockPos position,
                                         BlockState before, BlockState after, EventActor actor, String cause,
                                         Instant occurredAt) {
        var payload = new JsonObject();
        payload.addProperty("before", before.toString());
        payload.addProperty("after", after.toString());
        payload.addProperty("cause", cause);
        recordAt(eventType, level, position, actor, blockKey(before.isAir() ? after : before), payload, occurredAt);
    }

    public void recordFluidArrival(ServerLevel level, BlockPos position, BlockPos source,
                                   BlockState before, BlockState after, net.minecraft.core.Direction direction) {
        if (before.equals(after)) return;
        var payload = new JsonObject();
        payload.addProperty("before", before.toString());
        payload.addProperty("after", after.toString());
        payload.addProperty("source", source.toShortString());
        payload.addProperty("direction", direction.getName());
        payload.addProperty("boundary", "committed_spread");
        record("fluid_change", level, position, new BlockActor(blockKey(level.getBlockState(source))),
            blockKey(before.isAir() ? after : before), payload);
    }

    public void recordKick(ServerLevel level, BlockPos position, UUID actor, String reason) {
        var payload = new JsonObject();
        payload.addProperty("reason", reason);
        payload.addProperty("boundary", "server_disconnect");
        record("neoforge_kick", level, position, new PlayerActor(actor), null, payload);
    }

    public void recordSpongeAbsorb(ServerLevel level, BlockPos sponge, BlockPos absorbed,
                                   BlockState before, BlockState after) {
        var payload = new JsonObject();
        payload.addProperty("before", before.toString());
        payload.addProperty("after", after.toString());
        payload.addProperty("sponge", sponge.toShortString());
        record("sponge_absorb", level, absorbed,
            new BlockActor(blockKey(level.getBlockState(sponge))), blockKey(before), payload);
    }

    public void recordCauldronChange(ServerLevel level, BlockPos position, BlockState before,
                                     BlockState after, String reason, Entity actorEntity) {
        if (before.equals(after)) return;
        var payload = new JsonObject();
        payload.addProperty("before", before.toString());
        payload.addProperty("after", after.toString());
        payload.addProperty("reason", reason);
        EventActor actor = actorEntity instanceof Player player ? new PlayerActor(player.getUUID())
            : actorEntity == null ? null : new EntityActor(actorEntity.getUUID(),
                Key.key(BuiltInRegistries.ENTITY_TYPE.getKey(actorEntity.getType()).toString()));
        record("cauldron_level_change", level, position, actor, blockKey(before), payload);
    }

    public void recordBlockHarvest(ServerLevel level, BlockPos position, ServerPlayer player,
                                   BlockState before, BlockState after, String method) {
        if (before.equals(after)) return;
        var payload = new JsonObject();
        payload.addProperty("before", before.toString());
        payload.addProperty("after", after.toString());
        payload.addProperty("method", method);
        record("block_harvest", level, position, new PlayerActor(player.getUUID()), blockKey(before), payload);
    }

    private void recordLecternChange(ServerLevel level, BlockPos position, ServerPlayer player,
                                      ItemStack book, String action) {
        if (book.isEmpty()) return;
        var payload = new JsonObject();
        payload.addProperty("action", action);
        payload.add("book", itemContents(book));
        record("lectern_change", level, position, new PlayerActor(player.getUUID()), itemKey(book), payload);
    }

    void recordWorld(String eventType, ServerLevel level, JsonObject payload) {
        runtime.submit(new EventSubmission(type(eventType), NATIVE_PAYLOAD, Instant.now(), serverKey,
            Key.key(level.dimension().identifier().toString()), null, null, null,
            EventPayload.copyOf(payload.toString().getBytes(StandardCharsets.UTF_8))));
    }

    private EventSubmission submission(String eventType, ServerLevel level, BlockPos position, EventActor actor,
                                       Key target, JsonObject payload) {
        return new EventSubmission(type(eventType), NATIVE_PAYLOAD, Instant.now(), serverKey,
            Key.key(level.dimension().identifier().toString()),
            new BlockPosition(position.getX(), position.getY(), position.getZ()), actor, target,
            EventPayload.copyOf(payload.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static Key type(String name) { return Key.key("kansokusha", name); }
    static void register(KansokushaRuntime runtime, String... names) {
        for (var name : names) runtime.registerEventType(new EventTypeDefinition(type(name), NATIVE_PAYLOAD));
    }
    private static Key blockKey(BlockState state) { return Key.key(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()); }
    private static Key itemKey(ItemStack stack) { return Key.key(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); }

    private record OpenContainer(ServerLevel level, BlockPos position) { }
    private static final class ItemDelta {
        private final ItemStack template;
        private int amount;
        private ItemDelta(ItemStack template, int amount) { this.template = template; this.amount = amount; }
    }
}
