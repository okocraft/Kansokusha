package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.entity.EntityBreakByEntityEvent;
import io.papermc.paper.event.entity.EntityBreakEvent;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class PaperEntityLifecycleEventRelationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID ENTITY_ID =
        UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
    private static final UUID BREAKER_ID =
        UUID.fromString("323e4567-e89b-12d3-a456-426614174000");

    @Test
    void testGenericEntityPlaceUsesCanonicalSchemaAndDetachedItemSnapshot() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = placeListener(api);
        var world = PaperBlockEventTestSupport.world();
        var entity = entity(ArmorStand.class, world, ENTITY_ID, EntityType.ARMOR_STAND, 4.25, 65, -2.75);
        var usedItem = ItemStack.of(Material.ARMOR_STAND, 2);
        var player = player(world, usedItem);

        var event = Mockito.mock(EntityPlaceEvent.class);
        Mockito.when(event.getEntity()).thenReturn(entity);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(event.isCancelled()).thenReturn(false);

        listener.captureEntityPlace(event);
        usedItem.setAmount(9);
        Mockito.when(entity.getType()).thenReturn(EntityType.MINECART);
        listener.finalizeEntityPlace(event);

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperBlockPlaceListener.ENTITY_EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(4, 65, -3), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());

        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("example:world", string(payload, "world"));
        Assertions.assertEquals(
            PaperBlockPlaceListener.ENTITY_PLACE_SOURCE_EVENT,
            string(payload, "source_event")
        );
        Assertions.assertEquals(ENTITY_ID.toString(), string(payload.getCompoundOrEmpty("entity"), "uuid"));
        Assertions.assertEquals("armor_stand", string(payload.getCompoundOrEmpty("entity"), "type"));
        Assertions.assertEquals(PLAYER_ID.toString(), string(payload.getCompoundOrEmpty("actor"), "uuid"));
        Assertions.assertEquals("player", string(payload.getCompoundOrEmpty("actor"), "type"));
        Assertions.assertEquals("hand", string(payload, "hand"));
        Assertions.assertTrue(payload.contains("position"));
        Assertions.assertFalse(payload.contains("attached_block"));
        Assertions.assertEquals(
            2,
            PaperItemStackPayloadCodec.decode(
                payload.getCompoundOrEmpty("used_item")
            ).getAmount()
        );
        Assertions.assertEquals(0, listener.entityInFlightCount());
    }

    @Test
    void testHangingPlacementOwnsCanonicalRecordWhenGenericIsAlsoDispatched() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = placeListener(api);
        var world = PaperBlockEventTestSupport.world();
        var hanging = entity(Hanging.class, world, ENTITY_ID, EntityType.ITEM_FRAME, 12.5, 66, 8.5);
        var usedItem = ItemStack.of(Material.ITEM_FRAME, 1);
        var player = player(world, ItemStack.of(Material.ITEM_FRAME, 1));
        var attached = block(world, 12, 66, 8);

        var hangingEvent = Mockito.mock(HangingPlaceEvent.class);
        Mockito.when(hangingEvent.getEntity()).thenReturn(hanging);
        Mockito.when(hangingEvent.getPlayer()).thenReturn(player);
        Mockito.when(hangingEvent.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        Mockito.when(hangingEvent.getItemStack()).thenReturn(usedItem);
        Mockito.when(hangingEvent.getBlock()).thenReturn(attached);
        Mockito.when(hangingEvent.getBlockFace()).thenReturn(BlockFace.NORTH);
        Mockito.when(hangingEvent.isCancelled()).thenReturn(false);

        var genericEvent = Mockito.mock(EntityPlaceEvent.class);
        Mockito.when(genericEvent.getEntity()).thenReturn(hanging);
        Mockito.when(genericEvent.isCancelled()).thenReturn(false);

        listener.captureHangingPlace(hangingEvent);
        listener.captureEntityPlace(genericEvent);
        usedItem.setAmount(7);
        listener.finalizeHangingPlace(hangingEvent);
        listener.finalizeEntityPlace(genericEvent);

        var submission = onlySubmission(api);
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            PaperBlockPlaceListener.HANGING_PLACE_SOURCE_EVENT,
            string(payload, "source_event")
        );
        Assertions.assertEquals("item_frame", string(payload.getCompoundOrEmpty("entity"), "type"));
        Assertions.assertEquals("off_hand", string(payload, "hand"));
        Assertions.assertEquals("north", string(payload, "attached_face"));
        Assertions.assertTrue(payload.contains("attached_block"));
        Assertions.assertEquals(
            1,
            PaperItemStackPayloadCodec.decode(
                payload.getCompoundOrEmpty("used_item")
            ).getAmount()
        );
        Assertions.assertEquals(0, listener.entityInFlightCount());
        Mockito.verify(genericEvent, Mockito.never()).getPlayer();
    }

    @Test
    void testCancelledPlacementsAreNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = placeListener(api);
        var world = PaperBlockEventTestSupport.world();
        var entity = entity(ArmorStand.class, world, ENTITY_ID, EntityType.ARMOR_STAND, 1, 2, 3);
        var player = player(world, ItemStack.of(Material.ARMOR_STAND, 1));

        var event = Mockito.mock(EntityPlaceEvent.class);
        Mockito.when(event.getEntity()).thenReturn(entity);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.captureEntityPlace(event);
        listener.finalizeEntityPlace(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.entityInFlightCount());
    }

    @Test
    void testHangingBreakDualFiringUsesHangingAsCanonicalSource() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = breakListener(api);
        var world = PaperBlockEventTestSupport.world();
        var hanging = entity(Hanging.class, world, ENTITY_ID, EntityType.PAINTING, 20.75, 70, -4.25);
        var breaker = player(world, ItemStack.empty());
        var damageSource = damageSource("player_attack", breaker);

        var hangingEvent = Mockito.mock(HangingBreakByEntityEvent.class);
        Mockito.when(hangingEvent.getEntity()).thenReturn(hanging);
        Mockito.when(hangingEvent.getRemover()).thenReturn(breaker);
        Mockito.when(hangingEvent.getCause()).thenReturn(HangingBreakEvent.RemoveCause.ENTITY);
        Mockito.when(hangingEvent.getDamageSource()).thenReturn(damageSource);
        Mockito.when(hangingEvent.isCancelled()).thenReturn(false);

        var genericEvent = Mockito.mock(EntityBreakByEntityEvent.class);
        Mockito.when(genericEvent.getEntity()).thenReturn(hanging);
        Mockito.when(genericEvent.isCancelled()).thenReturn(false);

        listener.captureHangingBreak(hangingEvent);
        listener.captureEntityBreak(genericEvent);
        Mockito.when(hanging.getType()).thenReturn(EntityType.ITEM_FRAME);
        listener.finalizeHangingBreak(hangingEvent);
        listener.finalizeEntityBreak(genericEvent);

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperBlockBreakListener.ENTITY_EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(new BlockPosition(20, 70, -5), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());

        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            PaperBlockBreakListener.HANGING_BREAK_SOURCE_EVENT,
            string(payload, "source_event")
        );
        Assertions.assertEquals(ENTITY_ID.toString(), string(payload.getCompoundOrEmpty("broken_entity"), "uuid"));
        Assertions.assertEquals("painting", string(payload.getCompoundOrEmpty("broken_entity"), "type"));
        Assertions.assertEquals(PLAYER_ID.toString(), string(payload.getCompoundOrEmpty("breaker"), "uuid"));
        Assertions.assertEquals("entity", string(payload, "cause"));
        Assertions.assertEquals(
            "minecraft:player_attack",
            string(payload.getCompoundOrEmpty("source"), "damage_type")
        );
        Assertions.assertEquals(0, listener.entityInFlightCount());
        Mockito.verify(genericEvent, Mockito.never()).getRemover();
    }

    @Test
    void testGenericEntityBreakRecordsNonHangingBreakerWithoutPlayerSubject() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = breakListener(api);
        var world = PaperBlockEventTestSupport.world();
        var broken = entity(ArmorStand.class, world, ENTITY_ID, EntityType.ARMOR_STAND, 30.25, 64, 30.75);
        var breaker = entity(Entity.class, world, BREAKER_ID, EntityType.ZOMBIE, 31, 64, 31);
        var damageSource = damageSource("mob_attack", breaker);

        var event = Mockito.mock(EntityBreakByEntityEvent.class);
        Mockito.when(event.getEntity()).thenReturn(broken);
        Mockito.when(event.getRemover()).thenReturn(breaker);
        Mockito.when(event.getCause()).thenReturn(EntityBreakEvent.RemoveCause.ENTITY);
        Mockito.when(event.getDamageSource()).thenReturn(damageSource);
        Mockito.when(event.isCancelled()).thenReturn(false);

        listener.captureEntityBreak(event);
        Mockito.when(breaker.getType()).thenReturn(EntityType.SKELETON);
        listener.finalizeEntityBreak(event);

        var submission = onlySubmission(api);
        Assertions.assertNull(submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            PaperBlockBreakListener.ENTITY_BREAK_SOURCE_EVENT,
            string(payload, "source_event")
        );
        Assertions.assertEquals(BREAKER_ID.toString(), string(payload.getCompoundOrEmpty("breaker"), "uuid"));
        Assertions.assertEquals("zombie", string(payload.getCompoundOrEmpty("breaker"), "type"));
        Assertions.assertEquals(0, listener.entityInFlightCount());
    }

    @Test
    void testCancelledEntityBreakIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = breakListener(api);
        var world = PaperBlockEventTestSupport.world();
        var broken = entity(ArmorStand.class, world, ENTITY_ID, EntityType.ARMOR_STAND, 2, 3, 4);
        var breaker = entity(Entity.class, world, BREAKER_ID, EntityType.ZOMBIE, 2, 3, 5);

        var event = Mockito.mock(EntityBreakByEntityEvent.class);
        Mockito.when(event.getEntity()).thenReturn(broken);
        Mockito.when(event.getRemover()).thenReturn(breaker);
        var damageSource = damageSource("mob_attack", breaker);
        Mockito.when(event.getCause()).thenReturn(EntityBreakEvent.RemoveCause.ENTITY);
        Mockito.when(event.getDamageSource()).thenReturn(damageSource);
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.captureEntityBreak(event);
        listener.finalizeEntityBreak(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.entityInFlightCount());
    }

    private static PaperBlockPlaceListener placeListener(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        return PaperBlockPlaceListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static PaperBlockBreakListener breakListener(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        return PaperBlockBreakListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static Player player(World world, ItemStack heldItem) {
        var inventory = Mockito.mock(PlayerInventory.class);
        Mockito.when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(heldItem);
        Mockito.when(inventory.getItem(EquipmentSlot.OFF_HAND)).thenReturn(heldItem);

        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);
        Mockito.when(player.getWorld()).thenReturn(world);
        Mockito.when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    private static <T extends Entity> T entity(
        Class<T> type,
        World world,
        UUID uniqueId,
        EntityType entityType,
        double x,
        double y,
        double z
    ) {
        var entity = Mockito.mock(type);
        Mockito.when(entity.getUniqueId()).thenReturn(uniqueId);
        Mockito.when(entity.getType()).thenReturn(entityType);
        Mockito.when(entity.getWorld()).thenReturn(world);
        Mockito.when(entity.getLocation()).thenReturn(new Location(world, x, y, z));
        return entity;
    }

    private static Block block(World world, int x, int y, int z) {
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(y);
        Mockito.when(block.getZ()).thenReturn(z);
        return block;
    }

    private static DamageSource damageSource(String key, Entity directEntity) {
        var damageType = Mockito.mock(DamageType.class);
        Mockito.when(damageType.getKey()).thenReturn(
            new NamespacedKey("minecraft", key)
        );

        var source = Mockito.mock(DamageSource.class);
        Mockito.when(source.getDamageType()).thenReturn(damageType);
        Mockito.when(source.getDirectEntity()).thenReturn(directEntity);
        Mockito.when(source.getCausingEntity()).thenReturn(directEntity);
        Mockito.when(source.isIndirect()).thenReturn(false);
        return source;
    }

    private static EventSubmission onlySubmission(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.element();
    }

    private static String string(CompoundTag tag, String key) {
        return tag.getString(key).orElseThrow();
    }
}
