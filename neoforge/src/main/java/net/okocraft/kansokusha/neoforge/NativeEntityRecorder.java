package net.okocraft.kansokusha.neoforge;

import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.phys.Vec3;
import net.okocraft.kansokusha.neoforge.mixin.NativeItemFrameAccess;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Records entity changes at their native source and verifies cancellable interactions after they commit. */
public final class NativeEntityRecorder {
    private static final Set<String> AUDITED_SPAWNS = Set.of(
        "spawn_item_use", "dispenser", "bucket", "mob_summoned", "triggered");
    private static final ThreadLocal<ArrayDeque<PlacementUse>> PLACEMENT_USES =
        ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<InteractionUse>> ENTITY_USES =
        ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<DamageCause>> DAMAGE_CAUSES =
        ThreadLocal.withInitial(ArrayDeque::new);
    private static NativeEntityRecorder active;

    private final NativeEventRecorder recorder;
    private final Map<Entity, String> spawnReasons = new java.util.WeakHashMap<>();
    private final List<Runnable> pending = new ArrayList<>();

    NativeEntityRecorder(NativeEventRecorder recorder, KansokushaRuntime runtime) {
        this.recorder = recorder;
        active = this;
        NativeEventRecorder.register(runtime, "entity_place", "entity_break", "entity_spawn", "entity_death",
            "armor_stand_manipulate", "entity_leash_change", "item_frame_change", "entity_tame",
            "entity_name_change");
        NeoForge.EVENT_BUS.addListener(this::finalizeSpawn);
        NeoForge.EVENT_BUS.addListener(this::entityJoin);
        NeoForge.EVENT_BUS.addListener(this::entityLeave);
        NeoForge.EVENT_BUS.addListener(this::death);
        NeoForge.EVENT_BUS.addListener(this::tame);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    public static void beginPlacementUse(ServerPlayer player, ItemStack item, InteractionHand hand) {
        beginPlacementUse(player, item, hand, null);
    }

    public static void beginPlacementUse(ServerPlayer player, ItemStack item, InteractionHand hand, BlockPos clicked) {
        var use = new PlacementUse(player.getUUID(),
            BuiltInRegistries.ITEM.getKey(item.getItem()).toString(), hand.name().toLowerCase(Locale.ROOT));
        if (clicked != null && active != null && item.getItem() instanceof LeadItem
            && player.level() instanceof ServerLevel level && level.getBlockState(clicked).is(BlockTags.FENCES)) {
            for (Leashable leashable : Leashable.leashableInArea(level, Vec3.atCenterOf(clicked),
                target -> target.getLeashHolder() == player)) {
                Entity entity = (Entity) leashable;
                use.fenceInteractions.add(active.capture(player, entity, hand));
            }
        }
        PLACEMENT_USES.get().push(use);
    }

    public static void endPlacementUse(InteractionResult result) {
        var stack = PLACEMENT_USES.get();
        var use = stack.pop();
        use.committed = result.consumesAction();
        if (stack.isEmpty()) PLACEMENT_USES.remove();
        if (use.committed && active != null)
            for (var interaction : use.fenceInteractions) active.recordChanges(interaction);
    }

    public static void beginEntityInteraction(ServerPlayer player, Entity target, InteractionHand hand) {
        Interaction interaction = target == null || active == null
            || !(target instanceof ArmorStand || target instanceof ItemFrame || target instanceof Leashable
                || player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.NameTagItem)
            ? null : active.capture(player, target, hand);
        ENTITY_USES.get().push(new InteractionUse(interaction));
    }

    public static void endEntityInteraction() {
        var stack = ENTITY_USES.get();
        var use = stack.pop();
        if (stack.isEmpty()) ENTITY_USES.remove();
        if (use.interaction != null && active != null) active.recordChanges(use.interaction);
    }

    public static void beginHangingDamage(Entity target, DamageSource source) {
        DAMAGE_CAUSES.get().push(new DamageCause(target.getUUID(), actor(source.getEntity()), source.getMsgId(),
            source.getDirectEntity() != source.getEntity()));
    }

    public static void endHangingDamage() {
        var stack = DAMAGE_CAUSES.get();
        stack.pop();
        if (stack.isEmpty()) DAMAGE_CAUSES.remove();
    }

    private void finalizeSpawn(FinalizeSpawnEvent event) {
        spawnReasons.put(event.getEntity(), event.getSpawnType().name().toLowerCase(Locale.ROOT));
    }

    private void entityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        String reason = spawnReasons.remove(entity);
        if (event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos position = entity.blockPosition().immutable();
        PlacementUse use = PLACEMENT_USES.get().peek();
        if (use != null && matchesPlacementItem(use.item, entity)) {
            pending.add(() -> {
                if (!use.committed || event.isCanceled() || !entity.isAddedToLevel() || entity.isRemoved()) return;
                var payload = entityPayload(entity);
                payload.addProperty("item", use.item);
                payload.addProperty("hand", use.hand);
                recorder.record("entity_place", level, position, new PlayerActor(use.player), entityType(entity), payload);
            });
        }
        if ((reason == null || !AUDITED_SPAWNS.contains(reason)) && !(entity instanceof ArmorStand)) return;
        pending.add(() -> {
            if (event.isCanceled() || !entity.isAddedToLevel() || entity.isRemoved()) return;
            var payload = entityPayload(entity);
            payload.addProperty("reason", reason == null ? "placed" : reason);
            recorder.record("entity_spawn", level, position, null, entityType(entity), payload);
        });
    }

    private void entityLeave(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(event.getLevel() instanceof ServerLevel level) || !(entity instanceof HangingEntity)
            || entity instanceof LeashFenceKnotEntity || entity.getRemovalReason() == null) return;
        if (entity.getRemovalReason() != Entity.RemovalReason.KILLED
            && entity.getRemovalReason() != Entity.RemovalReason.DISCARDED) return;
        DamageCause cause = DAMAGE_CAUSES.get().peek();
        if (cause != null && !cause.target.equals(entity.getUUID())) cause = null;
        var payload = entityPayload(entity);
        payload.addProperty("removal_reason", entity.getRemovalReason().name().toLowerCase(Locale.ROOT));
        if (cause != null) {
            payload.addProperty("cause", cause.damageType);
            payload.addProperty("indirect", cause.indirect);
        }
        recorder.record("entity_break", level, entity.blockPosition().immutable(),
            cause == null ? null : cause.actor, entityType(entity), payload);
    }

    private void death(LivingDeathEvent event) {
        var victim = event.getEntity();
        if (victim instanceof Player || !(victim.level() instanceof ServerLevel level)) return;
        var killer = event.getSource().getEntity();
        if (!(killer instanceof Player) && !(victim instanceof AbstractVillager || victim instanceof ArmorStand)
            && !(victim instanceof TamableAnimal tame && tame.isTame()) && !victim.hasCustomName()) return;
        BlockPos position = victim.blockPosition().immutable();
        EventActor actor = actor(killer);
        var payload = entityPayload(victim);
        payload.addProperty("cause", event.getSource().getMsgId());
        pending.add(() -> {
            if (!event.isCanceled() && victim.isDeadOrDying())
                recorder.record("entity_death", level, position, actor, entityType(victim), payload);
        });
    }

    private void tame(AnimalTameEvent event) {
        if (!(event.getTamer() instanceof ServerPlayer player)
            || !(event.getAnimal() instanceof TamableAnimal animal)
            || !(animal.level() instanceof ServerLevel level)) return;
        BlockPos position = animal.blockPosition().immutable();
        var payload = entityPayload(animal);
        payload.addProperty("new_owner", player.getUUID().toString());
        pending.add(() -> {
            if (!event.isCanceled() && animal.isTame() && animal.getOwner() == player)
                recorder.record("entity_tame", level, position, new PlayerActor(player.getUUID()),
                    entityType(animal), payload);
        });
    }

    private void tick(ServerTickEvent.Post event) {
        for (var action : pending) action.run();
        pending.clear();
    }

    private Interaction capture(ServerPlayer player, Entity entity, InteractionHand hand) {
        return new Interaction((ServerLevel) entity.level(), entity, entity.blockPosition().immutable(),
            player.getUUID(), hand.name().toLowerCase(Locale.ROOT), player.getItemInHand(hand).copy(), snapshot(entity));
    }

    private void recordChanges(Interaction interaction) {
            Snapshot after = snapshot(interaction.entity);
            var before = interaction.before;
            var actor = new PlayerActor(interaction.player);
            var target = entityType(interaction.entity);
            if (interaction.entity instanceof ArmorStand) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack itemBefore = before.equipment.get(slot);
                    ItemStack itemAfter = after.equipment.get(slot);
                    if (ItemStack.matches(itemBefore, itemAfter)) continue;
                    var payload = entityPayload(interaction.entity);
                    payload.addProperty("hand", interaction.hand);
                    payload.addProperty("equipment_slot", slot.name().toLowerCase(Locale.ROOT));
                    payload.add("player_item_before", itemContents(interaction.heldItem));
                    payload.add("armor_stand_item_before", itemContents(itemBefore));
                    payload.add("armor_stand_item_after", itemContents(itemAfter));
                    recorder.record("armor_stand_manipulate", interaction.level, interaction.position, actor, target, payload);
                }
            }
            if (!java.util.Objects.equals(before.leashHolder, after.leashHolder)
                && interaction.entity instanceof Leashable) {
                var payload = entityPayload(interaction.entity);
                payload.addProperty("action", after.leashHolder == null ? "unleash" : "leash");
                if (before.leashHolder != null) payload.addProperty("holder_before", before.leashHolder.toString());
                if (after.leashHolder != null) payload.addProperty("holder_after", after.leashHolder.toString());
                payload.addProperty("hand", interaction.hand);
                recorder.record("entity_leash_change", interaction.level, interaction.position, actor, target, payload);
            }
            if ((!ItemStack.matches(before.frameItem, after.frameItem) || before.rotation != after.rotation)
                && interaction.entity instanceof ItemFrame frame) {
                var payload = entityPayload(interaction.entity);
                payload.addProperty("action", before.rotation != after.rotation ? "rotate"
                    : after.frameItem.isEmpty() ? "remove" : "place");
                payload.add("item_before", itemContents(before.frameItem));
                payload.add("item_after", itemContents(after.frameItem));
                payload.addProperty("rotation_before", before.rotation);
                payload.addProperty("rotation_after", after.rotation);
                payload.addProperty("fixed", ((NativeItemFrameAccess) frame).kansokusha$isFixed());
                recorder.record("item_frame_change", interaction.level, interaction.position, actor, target, payload);
            }
            if (!java.util.Objects.equals(before.name, after.name)) {
                var payload = entityPayload(interaction.entity);
                if (before.name != null) payload.addProperty("previous_custom_name", before.name);
                if (after.name != null) payload.addProperty("new_custom_name", after.name);
                recorder.record("entity_name_change", interaction.level, interaction.position, actor, target, payload);
            }
    }

    private static boolean matchesPlacementItem(String item, Entity entity) {
        if (entity instanceof ArmorStand) return item.endsWith(":armor_stand");
        if (entity instanceof ItemFrame) return item.endsWith(":item_frame")
            || item.endsWith(":glow_item_frame");
        if (entity instanceof HangingEntity) return item.endsWith(":painting");
        if (entity instanceof AbstractBoat) return item.endsWith("_boat") || item.endsWith("_raft");
        if (entity instanceof AbstractMinecart) return item.endsWith("minecart");
        return false;
    }

    private static Snapshot snapshot(Entity entity) {
        Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        if (entity instanceof ArmorStand stand) {
            for (EquipmentSlot slot : EquipmentSlot.values())
                equipment.put(slot, stand.getItemBySlot(slot).copy());
        }
        UUID holder = entity instanceof Leashable leashable && leashable.getLeashHolder() != null
            ? leashable.getLeashHolder().getUUID() : null;
        ItemStack item = entity instanceof ItemFrame frame ? frame.getItem().copy() : ItemStack.EMPTY;
        int rotation = entity instanceof ItemFrame frame ? frame.getRotation() : 0;
        Component name = entity.getCustomName();
        return new Snapshot(equipment, holder, item, rotation, name == null ? null : name.getString());
    }

    private static JsonObject itemContents(ItemStack item) {
        var result = new JsonObject();
        if (!item.isEmpty()) {
            result.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
            result.addProperty("amount", item.getCount());
            result.addProperty("components", item.getComponents().toString());
        }
        return result;
    }

    private static JsonObject entityPayload(Entity entity) {
        var payload = new JsonObject();
        payload.addProperty("uuid", entity.getUUID().toString());
        payload.addProperty("entity_type", entityType(entity).asString());
        return payload;
    }

    private static EventActor actor(Entity entity) {
        if (entity instanceof Player player) return new PlayerActor(player.getUUID());
        if (entity != null) return new EntityActor(entity.getUUID(), entityType(entity));
        return null;
    }

    private static Key entityType(Entity entity) {
        return Key.key(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    private static final class PlacementUse {
        private final UUID player;
        private final String item;
        private final String hand;
        private boolean committed;
        private final List<Interaction> fenceInteractions = new ArrayList<>();

        private PlacementUse(UUID player, String item, String hand) {
            this.player = player;
            this.item = item;
            this.hand = hand;
        }
    }

    private record InteractionUse(Interaction interaction) { }
    private record DamageCause(UUID target, EventActor actor, String damageType, boolean indirect) { }
    private record Snapshot(Map<EquipmentSlot, ItemStack> equipment, UUID leashHolder,
                            ItemStack frameItem, int rotation, String name) { }
    private record Interaction(ServerLevel level, Entity entity, BlockPos position, UUID player, String hand,
                               ItemStack heldItem, Snapshot before) { }
}
