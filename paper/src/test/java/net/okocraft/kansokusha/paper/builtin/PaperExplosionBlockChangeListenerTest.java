package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class PaperExplosionBlockChangeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testBlockExplosionUsesFinalListAndLowestPreStates() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var sourceBlock = PaperBlockEventTestSupport.block(
            world, 5, 64, 5, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var sourceState = PaperBlockEventTestSupport.state(
            world,
            sourceBlock,
            5,
            64,
            5,
            Blocks.RESPAWN_ANCHOR.defaultBlockState()
        );
        var first = PaperBlockEventTestSupport.block(
            world, 10, 64, 10, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        var removed = PaperBlockEventTestSupport.block(
            world, 11, 64, 10, Blocks.DEEPSLATE.defaultBlockState(), Material.DEEPSLATE
        );
        var added = PaperBlockEventTestSupport.block(
            world, 12, 64, 10, Blocks.DIAMOND_ORE.defaultBlockState(), Material.DIAMOND_ORE
        );
        var blocks = new ArrayList<Block>(List.of(first, removed));
        var event = Mockito.mock(BlockExplodeEvent.class);
        Mockito.when(event.getExplodedBlockState()).thenReturn(sourceState);
        Mockito.when(event.blockList()).thenReturn(blocks);

        listener.capture(event);
        Mockito.when(first.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());
        blocks.remove(removed);
        blocks.add(added);
        listener.finalizeEvent(event);

        Assertions.assertEquals(2, api.submissions.size());
        var byX = submissionsByX(api);
        Assertions.assertFalse(byX.containsKey(11));
        Assertions.assertEquals(OCCURRED_AT, byX.get(10).occurredAt());
        Assertions.assertEquals(OCCURRED_AT, byX.get(12).occurredAt());

        var firstPayload = PaperWorldMutationPayloadCodec.decode(byX.get(10).payload());
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(Blocks.STONE.defaultBlockState().asBlockData()),
            firstPayload.get("pre_state")
        );
        Assertions.assertEquals("block", firstPayload.getString("source_kind").orElseThrow());
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(
                Blocks.RESPAWN_ANCHOR.defaultBlockState().asBlockData()
            ),
            firstPayload.get("source_block_state")
        );

        var addedPayload = PaperWorldMutationPayloadCodec.decode(byX.get(12).payload());
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(
                Blocks.DIAMOND_ORE.defaultBlockState().asBlockData()
            ),
            addedPayload.get("pre_state")
        );
        Mockito.verify(first, Mockito.times(1)).getBlockData();
        Mockito.verify(added, Mockito.times(1)).getBlockData();
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testExplosionTntWaitsForPrimeResult() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);
        var sourceBlock = PaperBlockEventTestSupport.block(
            world, 40, 64, 39, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var sourceState = PaperBlockEventTestSupport.state(
            world,
            sourceBlock,
            40,
            64,
            39,
            Blocks.RESPAWN_ANCHOR.defaultBlockState()
        );
        var stone = PaperBlockEventTestSupport.block(
            world, 40, 64, 40, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        var cancelledTnt = PaperBlockEventTestSupport.block(
            world, 41, 64, 40, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var primedTnt = PaperBlockEventTestSupport.block(
            world, 42, 64, 40, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var event = Mockito.mock(BlockExplodeEvent.class);
        Mockito.when(event.getExplodedBlockState()).thenReturn(sourceState);
        Mockito.when(event.blockList())
            .thenReturn(new ArrayList<>(List.of(stone, cancelledTnt, primedTnt)));

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(2, listener.inFlightCount());

        var cancelledPrime = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(cancelledPrime.getBlock()).thenReturn(cancelledTnt);
        Mockito.when(cancelledPrime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
        Mockito.when(cancelledPrime.isCancelled()).thenReturn(true);
        listener.finalizeTntPrime(cancelledPrime);

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(1, listener.inFlightCount());

        var acceptedPrime = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(acceptedPrime.getBlock()).thenReturn(primedTnt);
        Mockito.when(acceptedPrime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
        listener.finalizeTntPrime(acceptedPrime);

        Assertions.assertEquals(2, api.submissions.size());
        var byX = submissionsByX(api);
        Assertions.assertTrue(byX.containsKey(40));
        Assertions.assertFalse(byX.containsKey(41));
        Assertions.assertTrue(byX.containsKey(42));
        Assertions.assertEquals(OCCURRED_AT, byX.get(40).occurredAt());
        Assertions.assertEquals(OCCURRED_AT, byX.get(42).occurredAt());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testEntityExplosionKeepsProjectileShooterAndOwner() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var destroyed = PaperBlockEventTestSupport.block(
            world, 20, 70, 20, Blocks.OAK_PLANKS.defaultBlockState(), Material.OAK_PLANKS
        );
        var shooterId = UUID.fromString("123e4567-e89b-12d3-a456-426614174010");
        var ownerId = UUID.fromString("123e4567-e89b-12d3-a456-426614174011");
        var projectileId = UUID.fromString("123e4567-e89b-12d3-a456-426614174012");
        var shooter = Mockito.mock(Player.class);
        Mockito.when(shooter.getUniqueId()).thenReturn(shooterId);
        Mockito.when(shooter.getType()).thenReturn(EntityType.PLAYER);
        var projectile = Mockito.mock(Projectile.class);
        Mockito.when(projectile.getUniqueId()).thenReturn(projectileId);
        Mockito.when(projectile.getType()).thenReturn(EntityType.ARROW);
        Mockito.when(projectile.getShooter()).thenReturn(shooter);
        Mockito.when(projectile.getOwnerUniqueId()).thenReturn(ownerId);
        var event = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(event.getEntity()).thenReturn(projectile);
        Mockito.when(event.getLocation()).thenReturn(new Location(world, 20.5, 70.5, 20.5));
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(destroyed)));

        listener.capture(event);
        listener.finalizeEvent(event);

        var submission = api.submissions.remove();
        Assertions.assertEquals(new PlayerSubject(shooterId), submission.subject());
        var payload = PaperWorldMutationPayloadCodec.decode(submission.payload());
        Assertions.assertEquals(
            projectileId.toString(),
            payload.getString("actor_entity_uuid").orElseThrow()
        );
        Assertions.assertEquals(
            shooterId.toString(),
            payload.getString("shooter_entity_uuid").orElseThrow()
        );
        Assertions.assertEquals(
            ownerId.toString(),
            payload.getString("owner_uuid").orElseThrow()
        );
        Assertions.assertEquals("entity", payload.getString("source_kind").orElseThrow());
    }

    @Test
    void testNonPlayerProjectileOwnerDoesNotBecomeCommonPlayerSubject() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var destroyed = PaperBlockEventTestSupport.block(
            world, 30, 70, 30, Blocks.NETHERRACK.defaultBlockState(), Material.NETHERRACK
        );
        var ghastId = UUID.fromString("123e4567-e89b-12d3-a456-426614174013");
        var projectileId = UUID.fromString("123e4567-e89b-12d3-a456-426614174014");
        var ghast = Mockito.mock(Ghast.class);
        Mockito.when(ghast.getUniqueId()).thenReturn(ghastId);
        Mockito.when(ghast.getType()).thenReturn(EntityType.GHAST);
        var projectile = Mockito.mock(Projectile.class);
        Mockito.when(projectile.getUniqueId()).thenReturn(projectileId);
        Mockito.when(projectile.getType()).thenReturn(EntityType.FIREBALL);
        Mockito.when(projectile.getShooter()).thenReturn(ghast);
        Mockito.when(projectile.getOwnerUniqueId()).thenReturn(ghastId);
        var event = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(event.getEntity()).thenReturn(projectile);
        Mockito.when(event.getLocation()).thenReturn(new Location(world, 30.5, 70.5, 30.5));
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(destroyed)));

        listener.capture(event);
        listener.finalizeEvent(event);

        var submission = api.submissions.remove();
        Assertions.assertNull(submission.subject());
        var payload = PaperWorldMutationPayloadCodec.decode(submission.payload());
        Assertions.assertEquals(
            ghastId.toString(),
            payload.getString("shooter_entity_uuid").orElseThrow()
        );
        Assertions.assertEquals(
            ghastId.toString(),
            payload.getString("owner_uuid").orElseThrow()
        );
    }

    @Test
    void testCancelledExplosionDropsCapture() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var destroyed = PaperBlockEventTestSupport.block(
            world, 1, 2, 3, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        var entity = Mockito.mock(Projectile.class);
        Mockito.when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(entity.getType()).thenReturn(EntityType.ARROW);
        var event = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(event.getEntity()).thenReturn(entity);
        Mockito.when(event.getLocation()).thenReturn(new Location(world, 1, 2, 3));
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(destroyed)));
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testConcurrentFoliaStyleExplosionsDoNotCrossSnapshots() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var events = new ArrayList<BlockExplodeEvent>();

        for (int i = 0; i < 24; i++) {
            var block = PaperBlockEventTestSupport.block(
                world,
                1000 + i,
                70,
                -i,
                (i & 1) == 0
                    ? Blocks.STONE.defaultBlockState()
                    : Blocks.DEEPSLATE.defaultBlockState(),
                (i & 1) == 0 ? Material.STONE : Material.DEEPSLATE
            );
            var sourceBlock = PaperBlockEventTestSupport.block(
                world, i, 64, i, Blocks.AIR.defaultBlockState(), Material.AIR
            );
            var sourceState = PaperBlockEventTestSupport.state(
                world,
                sourceBlock,
                i,
                64,
                i,
                Blocks.TNT.defaultBlockState()
            );
            var event = Mockito.mock(BlockExplodeEvent.class);
            Mockito.when(event.getExplodedBlockState()).thenReturn(sourceState);
            Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(block)));
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
        var byX = submissionsByX(api);
        for (int i = 0; i < 24; i++) {
            var expected = (i & 1) == 0
                ? Blocks.STONE.defaultBlockState()
                : Blocks.DEEPSLATE.defaultBlockState();
            var payload = PaperWorldMutationPayloadCodec.decode(byX.get(1000 + i).payload());
            Assertions.assertEquals(
                PaperBlockStatePayloadCodec.blockState(expected.asBlockData()),
                payload.get("pre_state")
            );
        }
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static HashMap<Integer, EventSubmission> submissionsByX(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        var result = new HashMap<Integer, EventSubmission>();
        for (var submission : api.submissions) {
            Assertions.assertNull(result.put(submission.position().x(), submission));
        }
        return result;
    }
}
