package net.okocraft.kansokusha.neoforge;

import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.EventActor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Committed hopper container moves aggregated by route and exact item components. */
public final class NativeContainerMoveRecorder {
    private static final Duration CHAIN_WINDOW = Duration.ofMinutes(5);
    private static final Duration CONTAINER_WINDOW = Duration.ofSeconds(15);
    private static final int MAX_PENDING = 100_000;
    private static final ThreadLocal<Deque<MoveContext>> OPERATIONS = new ThreadLocal<>();
    private static NativeContainerMoveRecorder active;

    private final NativeEventRecorder recorder;
    private final Map<TransferKey, Pending> pending = new LinkedHashMap<>();

    NativeContainerMoveRecorder(NativeEventRecorder recorder) {
        this.recorder = recorder;
        active = this;
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    public static MoveScope beginPush(ServerLevel level, BlockPos hopperPos, HopperBlockEntity hopper) {
        var facing = level.getBlockState(hopperPos).getValue(HopperBlock.FACING);
        return begin(new MoveContext(level, hopper, hopperPos.immutable(),
            hopperPos.relative(facing).immutable(), true));
    }

    public static MoveScope beginPull(ServerLevel level, Hopper hopper) {
        var hopperPos = hopper instanceof BlockEntity block ? block.getBlockPos()
            : hopper instanceof Entity entity ? entity.blockPosition()
            : BlockPos.containing(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        var sourcePos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
        return begin(new MoveContext(level, (Container) hopper, hopperPos,
            sourcePos, false));
    }

    private static MoveScope begin(MoveContext context) {
        if (active == null) return null;
        var stack = OPERATIONS.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            OPERATIONS.set(stack);
        }
        stack.push(context);
        return new MoveScope(context);
    }

    public static void end(MoveScope scope) {
        if (scope == null) return;
        var stack = OPERATIONS.get();
        stack.pop();
        if (stack.isEmpty()) OPERATIONS.remove();
    }

    public static void moved(Container from, Container to, ItemStack input, ItemStack remainder) {
        var recorder = active;
        var stack = OPERATIONS.get();
        if (recorder == null || stack == null || stack.isEmpty() || from == null || input.isEmpty()) return;
        if (!remainder.isEmpty() && !ItemStack.isSameItemSameComponents(input, remainder)) return;
        int moved = input.getCount() - remainder.getCount();
        if (moved <= 0) return;
        recorder.accept(stack.peek(), from, to, input.copyWithCount(1), moved);
    }

    public static void pickedUp(Container destination, ItemEntity entity, ItemStack before, boolean accepted) {
        var current = active;
        if (current == null || !accepted || !(entity.level() instanceof ServerLevel level) || before.isEmpty()) return;
        ItemStack after = entity.getItem();
        int moved = entity.isRemoved() ? before.getCount() : before.getCount() - after.getCount();
        if (moved <= 0) return;
        BlockPos location = directPosition(destination);
        EventActor actor = holderActor(destination);
        if (location == null || actor == null) return;
        var payload = new JsonObject();
        payload.addProperty("amount", moved);
        payload.addProperty("world_item", entity.getUUID().toString());
        current.recorder.record("container_pickup", level, location, actor, itemKey(before), payload);
    }

    private void accept(MoveContext context, Container from, Container to, ItemStack item, int amount) {
        Location source = locate(context, from);
        Location destination = locate(context, to);
        EventActor actor = holderActor(context.hopper);
        if (source == null || destination == null || actor == null) return;
        if (source.equals(destination)) return;
        var key = new TransferKey(source, destination, actor, context.push, new SimilarItem(item));
        Instant now = Instant.now();
        Pending existing = pending.get(key);
        if (existing != null && now.isBefore(existing.start.plus(existing.window))) {
            existing.attempts++;
            existing.amount += amount;
            return;
        }
        if (existing != null) {
            submit(key, existing);
            pending.remove(key);
        }
        var window = from instanceof Hopper && to instanceof Hopper ? CHAIN_WINDOW : CONTAINER_WINDOW;
        var created = new Pending(now, window, amount, 1, context);
        if (pending.size() >= MAX_PENDING) submit(key, created);
        else pending.put(key, created);
    }

    private static Location locate(MoveContext context, Container container) {
        BlockPos position = directPosition(container);
        UUID entityId = container instanceof Entity entity ? entity.getUUID() : null;
        if (position == null) {
            if (container == context.hopper) position = context.hopperPos;
            else if (container instanceof CompoundContainer) position = context.remotePos;
            else position = context.remotePos; // The vanilla caller resolved this container at this exact position.
        }
        return new Location(context.level.dimension().identifier().toString(), position.immutable(), entityId);
    }

    private static BlockPos directPosition(Container container) {
        if (container instanceof BlockEntity block) return block.getBlockPos().immutable();
        if (container instanceof Entity entity) return entity.blockPosition().immutable();
        return null;
    }

    private static EventActor holderActor(Container container) {
        if (container instanceof Entity entity)
            return new EntityActor(entity.getUUID(),
                Key.key(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()));
        if (container instanceof HopperBlockEntity) return new BlockActor(Key.key("minecraft:hopper"));
        return null;
    }

    private void tick(ServerTickEvent.Post event) {
        flushDue(Instant.now());
    }

    private void flushDue(Instant now) {
        Iterator<Map.Entry<TransferKey, Pending>> entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (now.isBefore(entry.getValue().start.plus(entry.getValue().window))) continue;
            submit(entry.getKey(), entry.getValue());
            entries.remove();
        }
    }

    void flushAll() {
        pending.forEach(this::submit);
        pending.clear();
        active = null;
    }

    private void submit(TransferKey key, Pending entry) {
        var payload = new JsonObject();
        payload.addProperty("from_world", key.source.world);
        payload.addProperty("from", key.source.position.toShortString());
        payload.addProperty("to_world", key.destination.world);
        payload.addProperty("to", key.destination.position.toShortString());
        payload.addProperty("initiator_role", key.push ? "source" : "destination");
        payload.addProperty("attempt_count", entry.attempts);
        payload.addProperty("item_amount", entry.amount);
        var item = new JsonObject();
        item.addProperty("item", itemKey(key.item.stack).asString());
        item.addProperty("amount", entry.amount);
        item.addProperty("components", key.item.stack.getComponents().toString());
        payload.add("item", item);
        recorder.recordAt("container_transfer", entry.context.level, entry.context.hopperPos,
            key.initiator, itemKey(key.item.stack), payload, entry.start);
    }

    private static Key itemKey(ItemStack item) {
        return Key.key(BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
    }

    public static final class MoveScope {
        private final MoveContext context;
        private MoveScope(MoveContext context) { this.context = context; }
    }

    private record MoveContext(ServerLevel level, Container hopper, BlockPos hopperPos,
                               BlockPos remotePos, boolean push) { }
    private record Location(String world, BlockPos position, UUID entityId) { }
    private record TransferKey(Location source, Location destination, EventActor initiator,
                               boolean push, SimilarItem item) { }
    private static final class SimilarItem {
        private final ItemStack stack;
        private SimilarItem(ItemStack item) { this.stack = item.copyWithCount(1); }
        @Override public boolean equals(Object value) {
            return value instanceof SimilarItem other && ItemStack.isSameItemSameComponents(stack, other.stack);
        }
        @Override public int hashCode() { return Objects.hash(stack.getItem(), stack.getComponents()); }
    }
    private static final class Pending {
        private final Instant start;
        private final Duration window;
        private long amount;
        private int attempts;
        private final MoveContext context;
        private Pending(Instant start, Duration window, long amount, int attempts, MoveContext context) {
            this.start = start;
            this.window = window;
            this.amount = amount;
            this.attempts = attempts;
            this.context = context;
        }
    }
}
