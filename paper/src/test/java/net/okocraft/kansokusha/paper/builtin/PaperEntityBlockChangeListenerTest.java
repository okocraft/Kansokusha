package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Material;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class PaperEntityBlockChangeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174020");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testPlayerActorGetsCommonSubjectAndImmutableBeforeTo() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var block = PaperBlockEventTestSupport.block(
            world, 10, 64, 10, Blocks.SAND.defaultBlockState(), Material.SAND
        );
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);
        var event = Mockito.mock(EntityChangeBlockEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getEntity()).thenReturn(player);
        Mockito.when(event.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());

        PaperListenerTestSupport.fire(listener, event);

        var submission = api.submissions.remove();
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(10, 64, 10), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(Blocks.SAND.defaultBlockState().asBlockData()),
            payload.get("before")
        );
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(Blocks.AIR.defaultBlockState().asBlockData()),
            payload.get("to")
        );
        Assertions.assertEquals(
            PLAYER_ID.toString(),
            payload.getString("actor_entity_uuid").orElseThrow()
        );
        Assertions.assertEquals("PLAYER", payload.getString("actor_entity_type").orElseThrow());
    }

    @Test
    void testNonPlayerActorHasNoCommonSubject() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var block = PaperBlockEventTestSupport.block(
            world, 2, 64, 2, Blocks.GRASS_BLOCK.defaultBlockState(), Material.GRASS_BLOCK
        );
        var actorId = UUID.fromString("123e4567-e89b-12d3-a456-426614174021");
        var enderman = Mockito.mock(Enderman.class);
        Mockito.when(enderman.getUniqueId()).thenReturn(actorId);
        Mockito.when(enderman.getType()).thenReturn(EntityType.ENDERMAN);
        var event = Mockito.mock(EntityChangeBlockEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getEntity()).thenReturn(enderman);
        Mockito.when(event.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());

        PaperListenerTestSupport.fire(listener, event);

        var submission = api.submissions.remove();
        Assertions.assertNull(submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("ENDERMAN", payload.getString("actor_entity_type").orElseThrow());
    }

    @Test
    void testProjectileTntTransitionIsOwnedByTntPrime() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var tnt = PaperBlockEventTestSupport.block(
            world, 20, 64, 20, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var projectile = Mockito.mock(Projectile.class);
        Mockito.when(projectile.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(projectile.getType()).thenReturn(EntityType.ARROW);
        var event = Mockito.mock(EntityChangeBlockEvent.class);
        Mockito.when(event.getBlock()).thenReturn(tnt);
        Mockito.when(event.getEntity()).thenReturn(projectile);
        Mockito.when(event.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testEntityBreakDoorSubclassIsRecordedByFallback() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var door = PaperBlockEventTestSupport.block(
            world, 21, 64, 20, Blocks.OAK_DOOR.defaultBlockState(), Material.OAK_DOOR
        );
        var actorId = UUID.fromString("123e4567-e89b-12d3-a456-426614174022");
        var zombie = Mockito.mock(Zombie.class);
        Mockito.when(zombie.getUniqueId()).thenReturn(actorId);
        Mockito.when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        var event = Mockito.mock(EntityBreakDoorEvent.class);
        Mockito.when(event.getBlock()).thenReturn(door);
        Mockito.when(event.getEntity()).thenReturn(zombie);
        Mockito.when(event.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(21, 64, 20), submission.position());
        Assertions.assertNull(submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(Blocks.OAK_DOOR.defaultBlockState().asBlockData()),
            payload.get("before")
        );
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(Blocks.AIR.defaultBlockState().asBlockData()),
            payload.get("to")
        );
        Assertions.assertEquals(actorId.toString(), payload.getString("actor_entity_uuid").orElseThrow());
        Assertions.assertEquals("ZOMBIE", payload.getString("actor_entity_type").orElseThrow());
    }

    @Test
    void testCancelledEntityChangeDoesNotSubmit() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var block = PaperBlockEventTestSupport.block(
            world, 3, 64, 3, Blocks.DIRT.defaultBlockState(), Material.DIRT
        );
        var actor = Mockito.mock(Enderman.class);
        Mockito.when(actor.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(actor.getType()).thenReturn(EntityType.ENDERMAN);
        var event = Mockito.mock(EntityChangeBlockEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getEntity()).thenReturn(actor);
        Mockito.when(event.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());
        Mockito.when(event.isCancelled()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }
}
