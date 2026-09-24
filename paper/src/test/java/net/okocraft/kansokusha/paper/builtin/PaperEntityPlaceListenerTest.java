package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPlaceEvent;
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

class PaperEntityPlaceListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID ENTITY_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174100");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174101");

    @Test
    void testGenericPlacementSnapshotsEntityActorAndItemAtLowest() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var entity = entity(world, EntityType.ARMOR_STAND, ENTITY_ID, 12.75, 64.0, -6.25);
        var item = ItemStack.of(Material.ARMOR_STAND, 1);
        var player = player(world, item);
        var event = Mockito.mock(EntityPlaceEvent.class);
        Mockito.when(event.getEntity()).thenReturn(entity);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getHand()).thenReturn(EquipmentSlot.HAND);

        listener.captureGeneric(event);
        item.setAmount(4);
        Mockito.when(entity.getLocation()).thenReturn(new Location(world, 99, 99, 99));
        listener.finalizeGeneric(event);

        var submission = onlySubmission(api);
        assertCommon(submission, PaperEntityPlaceListener.EVENT_TYPE, new BlockPosition(12, 64, -7));
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());

        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        assertEntity(payload.getCompoundOrEmpty("entity"), ENTITY_ID, "minecraft:armor_stand", 12.75, 64, -6.25);
        assertEntity(payload.getCompoundOrEmpty("actor"), PLAYER_ID, "minecraft:player", 1.5, 65, 2.5);
        Assertions.assertEquals("hand", payload.getString("hand").orElseThrow());
        Assertions.assertEquals(
            1,
            PaperItemStackPayloadCodec.decode(payload.getCompoundOrEmpty("used_item")).getAmount()
        );
        Assertions.assertEquals(
            PaperEntityPlaceListener.GENERIC_SOURCE_EVENT,
            payload.getString("source_event").orElseThrow()
        );
        Assertions.assertFalse(payload.contains("hanging"));
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testHangingPlacementUsesCanonicalItemAndContext() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var hanging = hanging(world, ENTITY_ID, 20.5, 70.25, 30.5);
        var player = player(world, ItemStack.of(Material.STICK, 1));
        var attached = Mockito.mock(Block.class);
        Mockito.when(attached.getX()).thenReturn(20);
        Mockito.when(attached.getY()).thenReturn(70);
        Mockito.when(attached.getZ()).thenReturn(29);
        var item = ItemStack.of(Material.ITEM_FRAME, 1);
        var event = Mockito.mock(HangingPlaceEvent.class);
        Mockito.when(event.getEntity()).thenReturn(hanging);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        Mockito.when(event.getItemStack()).thenReturn(item);
        Mockito.when(event.getBlock()).thenReturn(attached);
        Mockito.when(event.getBlockFace()).thenReturn(BlockFace.SOUTH);

        listener.captureHanging(event);
        item.setAmount(3);
        listener.finalizeHanging(event);

        var submission = onlySubmission(api);
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            PaperEntityPlaceListener.HANGING_SOURCE_EVENT,
            payload.getString("source_event").orElseThrow()
        );
        Assertions.assertEquals("off_hand", payload.getString("hand").orElseThrow());
        Assertions.assertEquals(
            1,
            PaperItemStackPayloadCodec.decode(payload.getCompoundOrEmpty("used_item")).getAmount()
        );
        var context = payload.getCompoundOrEmpty("hanging");
        Assertions.assertEquals("south", context.getString("face").orElseThrow());
        var attachedBlock = context.getCompoundOrEmpty("attached_block");
        Assertions.assertEquals(20, attachedBlock.getInt("x").orElseThrow());
        Assertions.assertEquals(70, attachedBlock.getInt("y").orElseThrow());
        Assertions.assertEquals(29, attachedBlock.getInt("z").orElseThrow());
    }

    @Test
    void testCancelledPlacementsAreDropped() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var genericEntity = entity(world, EntityType.ARMOR_STAND, ENTITY_ID, 1, 2, 3);
        var generic = Mockito.mock(EntityPlaceEvent.class);
        Mockito.when(generic.getEntity()).thenReturn(genericEntity);
        Mockito.when(generic.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(generic.isCancelled()).thenReturn(true);

        var hangingEntity = hanging(world, ENTITY_ID, 2, 3, 4);
        var hangingBlock = Mockito.mock(Block.class);
        var hanging = Mockito.mock(HangingPlaceEvent.class);
        Mockito.when(hanging.getEntity()).thenReturn(hangingEntity);
        Mockito.when(hanging.getBlock()).thenReturn(hangingBlock);
        Mockito.when(hanging.getBlockFace()).thenReturn(BlockFace.NORTH);
        Mockito.when(hanging.isCancelled()).thenReturn(true);

        listener.captureGeneric(generic);
        listener.finalizeGeneric(generic);
        listener.captureHanging(hanging);
        listener.finalizeHanging(hanging);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static PaperEntityPlaceListener listener(PaperBlockEventTestSupport.RecordingApi api) {
        return PaperEntityPlaceListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static Entity entity(
        World world,
        EntityType type,
        UUID id,
        double x,
        double y,
        double z
    ) {
        var entity = Mockito.mock(Entity.class);
        Mockito.when(entity.getUniqueId()).thenReturn(id);
        Mockito.when(entity.getType()).thenReturn(type);
        Mockito.when(entity.getWorld()).thenReturn(world);
        Mockito.when(entity.getLocation()).thenReturn(new Location(world, x, y, z));
        return entity;
    }

    private static Hanging hanging(World world, UUID id, double x, double y, double z) {
        var hanging = Mockito.mock(Hanging.class);
        Mockito.when(hanging.getUniqueId()).thenReturn(id);
        Mockito.when(hanging.getType()).thenReturn(EntityType.ITEM_FRAME);
        Mockito.when(hanging.getWorld()).thenReturn(world);
        Mockito.when(hanging.getLocation()).thenReturn(new Location(world, x, y, z));
        return hanging;
    }

    private static Player player(World world, ItemStack mainHand) {
        var inventory = Mockito.mock(PlayerInventory.class);
        Mockito.when(inventory.getItemInMainHand()).thenReturn(mainHand);
        Mockito.when(inventory.getItemInOffHand()).thenReturn(mainHand);
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);
        Mockito.when(player.getWorld()).thenReturn(world);
        Mockito.when(player.getLocation()).thenReturn(new Location(world, 1.5, 65, 2.5));
        Mockito.when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    private static EventSubmission onlySubmission(PaperBlockEventTestSupport.RecordingApi api) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.remove();
    }

    private static void assertCommon(EventSubmission submission, Key type, BlockPosition position) {
        Assertions.assertEquals(type, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(PaperBlockEventTestSupport.SERVER_KEY, submission.serverKey());
        Assertions.assertEquals(Key.key("example", "world"), submission.worldKey());
        Assertions.assertEquals(position, submission.position());
    }

    private static void assertEntity(
        CompoundTag entity,
        UUID id,
        String type,
        double x,
        double y,
        double z
    ) {
        Assertions.assertEquals(id.toString(), entity.getString("uuid").orElseThrow());
        Assertions.assertEquals(type, entity.getString("type").orElseThrow());
        Assertions.assertEquals("example:world", entity.getString("world").orElseThrow());
        var position = entity.getCompoundOrEmpty("position");
        Assertions.assertEquals(x, position.getDouble("x").orElseThrow());
        Assertions.assertEquals(y, position.getDouble("y").orElseThrow());
        Assertions.assertEquals(z, position.getDouble("z").orElseThrow());
    }
}
