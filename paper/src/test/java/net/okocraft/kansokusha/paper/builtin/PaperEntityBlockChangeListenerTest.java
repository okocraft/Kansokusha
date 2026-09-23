package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Material;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

        listener.capture(event);
        Mockito.when(block.getBlockData()).thenReturn(Blocks.STONE.defaultBlockState().asBlockData());
        Mockito.when(event.getBlockData()).thenReturn(Blocks.DIRT.defaultBlockState().asBlockData());
        listener.finalizeEvent(event);

        var submission = api.submissions.remove();
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(10, 64, 10), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        var payload = PaperWorldMutationPayloadCodec.decode(submission.payload());
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
        Mockito.verify(block, Mockito.times(1)).getBlockData();
        Mockito.verify(event, Mockito.times(1)).getBlockData();
        Assertions.assertEquals(0, listener.inFlightCount());
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

        listener.capture(event);
        listener.finalizeEvent(event);

        var submission = api.submissions.remove();
        Assertions.assertNull(submission.subject());
        var payload = PaperWorldMutationPayloadCodec.decode(submission.payload());
        Assertions.assertEquals("ENDERMAN", payload.getString("actor_entity_type").orElseThrow());
    }

    @Test
    void testSpecificEntityBreakDoorSubclassIsNotRecordedByFallback() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var event = Mockito.mock(EntityBreakDoorEvent.class);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
        Mockito.verify(event, Mockito.never()).getBlock();
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

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testConcurrentFoliaStyleEntityChangesDoNotCrossSnapshots() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var events = new ArrayList<EntityChangeBlockEvent>();
        var expectedActors = new HashMap<Integer, UUID>();

        for (int i = 0; i < 32; i++) {
            var x = 1000 + i;
            var actorId = UUID.nameUUIDFromBytes(("entity-" + i).getBytes(StandardCharsets.UTF_8));
            expectedActors.put(x, actorId);
            var block = PaperBlockEventTestSupport.block(
                world,
                x,
                64,
                -i,
                (i & 1) == 0 ? Blocks.SAND.defaultBlockState() : Blocks.GRAVEL.defaultBlockState(),
                (i & 1) == 0 ? Material.SAND : Material.GRAVEL
            );
            var actor = Mockito.mock(Enderman.class);
            Mockito.when(actor.getUniqueId()).thenReturn(actorId);
            Mockito.when(actor.getType()).thenReturn(EntityType.ENDERMAN);
            var event = Mockito.mock(EntityChangeBlockEvent.class);
            Mockito.when(event.getBlock()).thenReturn(block);
            Mockito.when(event.getEntity()).thenReturn(actor);
            Mockito.when(event.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());
            events.add(event);
        }

        var executor = Executors.newFixedThreadPool(8);
        try {
            var captures = events.stream()
                .map(event -> executor.submit(() -> listener.capture(event)))
                .toList();
            for (var task : captures) {
                task.get();
            }
            var finalizers = events.stream()
                .map(event -> executor.submit(() -> listener.finalizeEvent(event)))
                .toList();
            for (var task : finalizers) {
                task.get();
            }
        } finally {
            executor.shutdown();
            Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        Assertions.assertEquals(events.size(), api.submissions.size());
        for (var submission : api.submissions) {
            var payload = PaperWorldMutationPayloadCodec.decode(submission.payload());
            Assertions.assertEquals(
                expectedActors.get(submission.position().x()).toString(),
                payload.getString("actor_entity_uuid").orElseThrow()
            );
        }
        Assertions.assertEquals(0, listener.inFlightCount());
    }
}
