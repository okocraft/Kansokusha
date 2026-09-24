package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.paper.api.PaperKansokusha;
import org.bukkit.ExplosionResult;
import org.bukkit.GameRules;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.EnderDragon;
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
        listener.captureTntPrime(cancelledPrime);
        listener.finalizeTntPrime(cancelledPrime);

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(1, listener.inFlightCount());

        var acceptedPrime = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(acceptedPrime.getBlock()).thenReturn(primedTnt);
        Mockito.when(acceptedPrime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
        listener.captureTntPrime(acceptedPrime);
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
    void testTntExplodesFalseSubmitsDestroyedTntImmediately() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(false);
        var sourceBlock = PaperBlockEventTestSupport.block(
            world, 50, 64, 49, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var sourceState = PaperBlockEventTestSupport.state(
            world, sourceBlock, 50, 64, 49, Blocks.RESPAWN_ANCHOR.defaultBlockState()
        );
        var tnt = PaperBlockEventTestSupport.block(
            world, 50, 64, 50, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var event = Mockito.mock(BlockExplodeEvent.class);
        Mockito.when(event.getExplodedBlockState()).thenReturn(sourceState);
        Mockito.when(event.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(tnt)));

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(new BlockPosition(50, 64, 50), api.submissions.remove().position());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testDragonYieldZeroDestroysTntWithoutPrimeFollowUp() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var tnt = PaperBlockEventTestSupport.block(
            world, 51, 64, 51, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);
        var event = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(event.getEntity()).thenReturn(dragon);
        Mockito.when(event.getLocation()).thenReturn(new Location(world, 51.5, 64.5, 51.5));
        Mockito.when(event.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(event.getYield()).thenReturn(0.0F);
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(tnt)));

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(new BlockPosition(51, 64, 51), api.submissions.remove().position());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testDragonNonZeroYieldWaitsForLegacyPrimeGate() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var tnt = PaperBlockEventTestSupport.block(
            world, 52, 64, 52, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);
        var event = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(event.getEntity()).thenReturn(dragon);
        Mockito.when(event.getLocation()).thenReturn(new Location(world, 52.5, 64.5, 52.5));
        Mockito.when(event.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(event.getYield()).thenReturn(1.0F);
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(tnt)));

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, listener.inFlightCount());

        var legacy = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(legacy.getBlock()).thenReturn(tnt);
        Mockito.when(legacy.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION);
        Mockito.when(legacy.isCancelled()).thenReturn(true);
        listener.captureTntPrime(legacy);
        listener.finalizeTntPrime(legacy);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testTriggerBlockDoesNotRecordDestroyOrBogusTntPrime() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var explosionListener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var tntListener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);
        var stone = PaperBlockEventTestSupport.block(
            world, 53, 64, 53, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        var tnt = PaperBlockEventTestSupport.block(
            world, 54, 64, 53, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var source = Mockito.mock(Projectile.class);
        Mockito.when(source.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(source.getType()).thenReturn(EntityType.WIND_CHARGE);
        var explosion = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(explosion.getEntity()).thenReturn(source);
        Mockito.when(explosion.getLocation()).thenReturn(new Location(world, 53.5, 64.5, 53.5));
        Mockito.when(explosion.getExplosionResult()).thenReturn(ExplosionResult.TRIGGER_BLOCK);
        Mockito.when(explosion.blockList()).thenReturn(new ArrayList<>(List.of(stone, tnt)));

        explosionListener.capture(explosion);
        explosionListener.finalizeEvent(explosion);

        Assertions.assertTrue(api.submissions.isEmpty());

        var prime = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(prime.getBlock()).thenReturn(tnt);
        Mockito.when(prime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
        tntListener.capture(prime);
        tntListener.finalizeEvent(prime);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, explosionListener.inFlightCount());
        Assertions.assertEquals(0, tntListener.inFlightCount());
    }

    @Test
    void testDuplicateFinalTntFirstPrimeCancelledThenSecondAccepted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);
        var sourceBlock = PaperBlockEventTestSupport.block(
            world, 55, 64, 54, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var sourceState = PaperBlockEventTestSupport.state(
            world, sourceBlock, 55, 64, 54, Blocks.RESPAWN_ANCHOR.defaultBlockState()
        );
        var tnt = PaperBlockEventTestSupport.block(
            world, 55, 64, 55, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var event = Mockito.mock(BlockExplodeEvent.class);
        Mockito.when(event.getExplodedBlockState()).thenReturn(sourceState);
        Mockito.when(event.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(event.blockList()).thenReturn(new ArrayList<>(List.of(tnt, tnt)));

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, listener.inFlightCount());

        var firstPrime = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(firstPrime.getBlock()).thenReturn(tnt);
        Mockito.when(firstPrime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
        Mockito.when(firstPrime.isCancelled()).thenReturn(true);
        listener.captureTntPrime(firstPrime);
        listener.finalizeTntPrime(firstPrime);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, listener.inFlightCount());

        var secondPrime = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(secondPrime.getBlock()).thenReturn(tnt);
        Mockito.when(secondPrime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
        listener.captureTntPrime(secondPrime);
        listener.finalizeTntPrime(secondPrime);

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(new BlockPosition(55, 64, 55), api.submissions.remove().position());
        Assertions.assertEquals(0, listener.inFlightCount());
    }
    @Test
    void testReentrantAcceptedInnerDestroyInvalidatesOuterPending() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);
        var tnt = PaperBlockEventTestSupport.block(
            world, 56, 64, 56, Blocks.TNT.defaultBlockState(), Material.TNT
        );

        var outerSourceBlock = PaperBlockEventTestSupport.block(
            world, 1, 64, 1, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var outerState = PaperBlockEventTestSupport.state(
            world, outerSourceBlock, 1, 64, 1, Blocks.RESPAWN_ANCHOR.defaultBlockState()
        );
        var outer = Mockito.mock(BlockExplodeEvent.class);
        Mockito.when(outer.getExplodedBlockState()).thenReturn(outerState);
        Mockito.when(outer.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(outer.blockList()).thenReturn(new ArrayList<>(List.of(tnt)));

        var innerSourceBlock = PaperBlockEventTestSupport.block(
            world, 2, 64, 2, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var innerState = PaperBlockEventTestSupport.state(
            world, innerSourceBlock, 2, 64, 2, Blocks.TNT.defaultBlockState()
        );
        var inner = Mockito.mock(BlockExplodeEvent.class);
        Mockito.when(inner.getExplodedBlockState()).thenReturn(innerState);
        Mockito.when(inner.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(inner.blockList()).thenReturn(new ArrayList<>(List.of(tnt)));

        listener.capture(outer);
        listener.finalizeEvent(outer);
        listener.capture(inner);
        listener.finalizeEvent(inner);

        Assertions.assertEquals(2, listener.inFlightCount());

        var innerPrime = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(innerPrime.getBlock()).thenReturn(tnt);
        Mockito.when(innerPrime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
        listener.captureTntPrime(innerPrime);
        listener.finalizeTntPrime(innerPrime);

        Assertions.assertEquals(1, api.submissions.size());
        var innerSubmission = api.submissions.remove();
        var innerPayload = PaperWorldMutationPayloadCodec.decode(innerSubmission.payload());
        Assertions.assertEquals(
            PaperBlockEventTestSupport.position(new BlockPosition(2, 64, 2)),
            innerPayload.get("source_block")
        );
        Assertions.assertEquals(0, listener.inFlightCount());
    }
    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testDragonAcceptedLegacyExplosionRecordsBlockChangeAndTntPrime() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var explosionListener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var tntListener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);
        var tnt = PaperBlockEventTestSupport.block(
            world, 58, 64, 58, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);
        var explosion = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(explosion.getEntity()).thenReturn(dragon);
        Mockito.when(explosion.getLocation()).thenReturn(new Location(world, 58.5, 64.5, 58.5));
        Mockito.when(explosion.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(explosion.getYield()).thenReturn(1.0F);
        Mockito.when(explosion.blockList()).thenReturn(new ArrayList<>(List.of(tnt)));

        explosionListener.capture(explosion);
        explosionListener.finalizeEvent(explosion);

        var legacy = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(legacy.getBlock()).thenReturn(tnt);
        Mockito.when(legacy.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION);
        Mockito.when(legacy.getPrimerEntity()).thenReturn(dragon);

        explosionListener.captureTntPrime(legacy);
        tntListener.captureTntPrime(legacy);
        tntListener.finalizeTntPrime(legacy);
        explosionListener.finalizeTntPrime(legacy);

        Assertions.assertEquals(2, api.submissions.size());
        Assertions.assertEquals(
            1,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(
                    PaperExplosionBlockChangeListener.EVENT_TYPE
                ))
                .count()
        );
        Assertions.assertEquals(
            1,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(
                    PaperTntPrimeListener.EVENT_TYPE
                ))
                .count()
        );
        Assertions.assertEquals(0, explosionListener.inFlightCount());
        Assertions.assertEquals(0, tntListener.inFlightCount());
    }

    @Test
    void testNestedDestroyThenTriggerKeepsOuterCancellationIndependent() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var explosionListener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var tntListener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);
        var tnt = PaperBlockEventTestSupport.block(
            world, 59, 64, 59, Blocks.TNT.defaultBlockState(), Material.TNT
        );

        var outerSource = PaperBlockEventTestSupport.block(
            world, 3, 64, 3, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var outerState = PaperBlockEventTestSupport.state(
            world, outerSource, 3, 64, 3, Blocks.RESPAWN_ANCHOR.defaultBlockState()
        );
        var outer = Mockito.mock(BlockExplodeEvent.class);
        Mockito.when(outer.getExplodedBlockState()).thenReturn(outerState);
        Mockito.when(outer.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(outer.blockList()).thenReturn(new ArrayList<>(List.of(tnt)));
        explosionListener.capture(outer);
        explosionListener.finalizeEvent(outer);

        var triggerSource = Mockito.mock(Projectile.class);
        Mockito.when(triggerSource.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(triggerSource.getType()).thenReturn(EntityType.WIND_CHARGE);
        var inner = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(inner.getEntity()).thenReturn(triggerSource);
        Mockito.when(inner.getLocation()).thenReturn(new Location(world, 59.5, 64.5, 59.5));
        Mockito.when(inner.getExplosionResult()).thenReturn(ExplosionResult.TRIGGER_BLOCK);
        Mockito.when(inner.blockList()).thenReturn(new ArrayList<>(List.of(tnt)));
        explosionListener.capture(inner);
        explosionListener.finalizeEvent(inner);

        var triggerPrime = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(triggerPrime.getBlock()).thenReturn(tnt);
        Mockito.when(triggerPrime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
        explosionListener.captureTntPrime(triggerPrime);
        tntListener.capture(triggerPrime);
        tntListener.finalizeEvent(triggerPrime);
        explosionListener.finalizeTntPrime(triggerPrime);

        Assertions.assertTrue(api.submissions.isEmpty());

        var outerPrime = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(outerPrime.getBlock()).thenReturn(tnt);
        Mockito.when(outerPrime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
        Mockito.when(outerPrime.isCancelled()).thenReturn(true);
        explosionListener.captureTntPrime(outerPrime);
        tntListener.capture(outerPrime);
        explosionListener.finalizeTntPrime(outerPrime);
        tntListener.finalizeEvent(outerPrime);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, explosionListener.inFlightCount());
    }

    @Test
    void testNestedTriggerThenDestroyRecordsInnerAndInvalidatesOuterTrigger() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var explosionListener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var tntListener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);
        var tnt = PaperBlockEventTestSupport.block(
            world, 60, 64, 60, Blocks.TNT.defaultBlockState(), Material.TNT
        );

        var triggerSource = Mockito.mock(Projectile.class);
        Mockito.when(triggerSource.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(triggerSource.getType()).thenReturn(EntityType.WIND_CHARGE);
        var outer = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(outer.getEntity()).thenReturn(triggerSource);
        Mockito.when(outer.getLocation()).thenReturn(new Location(world, 60.5, 64.5, 60.5));
        Mockito.when(outer.getExplosionResult()).thenReturn(ExplosionResult.TRIGGER_BLOCK);
        Mockito.when(outer.blockList()).thenReturn(new ArrayList<>(List.of(tnt)));
        explosionListener.capture(outer);
        explosionListener.finalizeEvent(outer);

        var innerSource = PaperBlockEventTestSupport.block(
            world, 4, 64, 4, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var innerState = PaperBlockEventTestSupport.state(
            world, innerSource, 4, 64, 4, Blocks.RESPAWN_ANCHOR.defaultBlockState()
        );
        var inner = Mockito.mock(BlockExplodeEvent.class);
        Mockito.when(inner.getExplodedBlockState()).thenReturn(innerState);
        Mockito.when(inner.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(inner.blockList()).thenReturn(new ArrayList<>(List.of(tnt)));
        explosionListener.capture(inner);
        explosionListener.finalizeEvent(inner);

        var innerPrime = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(innerPrime.getBlock()).thenReturn(tnt);
        Mockito.when(innerPrime.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
        explosionListener.captureTntPrime(innerPrime);
        tntListener.capture(innerPrime);
        explosionListener.finalizeTntPrime(innerPrime);
        tntListener.finalizeEvent(innerPrime);

        Assertions.assertEquals(2, api.submissions.size());
        Assertions.assertEquals(
            1,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(
                    PaperExplosionBlockChangeListener.EVENT_TYPE
                ))
                .count()
        );
        Assertions.assertEquals(
            1,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(
                    PaperTntPrimeListener.EVENT_TYPE
                ))
                .count()
        );
        Assertions.assertEquals(0, explosionListener.inFlightCount());
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testDragonForeignTntUsesListedTypeButDragonWorldPreState() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var explosionListener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var tntListener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var foreignWorld = PaperBlockEventTestSupport.world();
        Mockito.when(foreignWorld.getKey()).thenReturn(new NamespacedKey("example", "foreign"));
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);

        var dragonStone = PaperBlockEventTestSupport.block(
            world, 61, 64, 61, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        Mockito.when(world.getBlockAt(61, 64, 61)).thenReturn(dragonStone);
        var foreignTnt = PaperBlockEventTestSupport.block(
            foreignWorld, 61, 64, 61, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var blocks = new ArrayList<Block>();
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);
        var explosion = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(explosion.getEntity()).thenReturn(dragon);
        Mockito.when(explosion.getLocation()).thenReturn(new Location(world, 61.5, 64.5, 61.5));
        Mockito.when(explosion.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(explosion.getYield()).thenReturn(1.0F);
        Mockito.when(explosion.blockList()).thenReturn(blocks);

        explosionListener.capture(explosion);
        blocks.add(foreignTnt);
        explosionListener.finalizeEvent(explosion);

        var legacy = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(legacy.getBlock()).thenReturn(dragonStone);
        Mockito.when(legacy.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION);
        Mockito.when(legacy.getPrimerEntity()).thenReturn(dragon);

        explosionListener.captureTntPrime(legacy);
        tntListener.captureTntPrime(legacy);
        tntListener.finalizeTntPrime(legacy);
        explosionListener.finalizeTntPrime(legacy);

        Assertions.assertEquals(2, api.submissions.size());
        var blockChange = api.submissions.stream()
            .filter(submission -> submission.eventType().equals(
                PaperExplosionBlockChangeListener.EVENT_TYPE
            ))
            .findFirst()
            .orElseThrow();
        var payload = PaperWorldMutationPayloadCodec.decode(blockChange.payload());
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(
                Blocks.STONE.defaultBlockState().asBlockData()
            ),
            payload.get("pre_state")
        );
        Assertions.assertEquals(
            1,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(PaperTntPrimeListener.EVENT_TYPE))
                .count()
        );
        Assertions.assertEquals(0, explosionListener.inFlightCount());
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testDragonForeignStoneDoesNotPrimeDragonWorldTnt() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var explosionListener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var tntListener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var foreignWorld = PaperBlockEventTestSupport.world();
        Mockito.when(foreignWorld.getKey()).thenReturn(new NamespacedKey("example", "foreign"));
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);

        var dragonTnt = PaperBlockEventTestSupport.block(
            world, 62, 64, 62, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        Mockito.when(world.getBlockAt(62, 64, 62)).thenReturn(dragonTnt);
        var foreignStone = PaperBlockEventTestSupport.block(
            foreignWorld, 62, 64, 62, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        var blocks = new ArrayList<Block>();
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);
        var explosion = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(explosion.getEntity()).thenReturn(dragon);
        Mockito.when(explosion.getLocation()).thenReturn(new Location(world, 62.5, 64.5, 62.5));
        Mockito.when(explosion.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(explosion.getYield()).thenReturn(1.0F);
        Mockito.when(explosion.blockList()).thenReturn(blocks);

        explosionListener.capture(explosion);
        blocks.add(foreignStone);
        explosionListener.finalizeEvent(explosion);

        var legacy = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(legacy.getBlock()).thenReturn(dragonTnt);
        Mockito.when(legacy.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION);
        Mockito.when(legacy.getPrimerEntity()).thenReturn(dragon);

        explosionListener.captureTntPrime(legacy);
        tntListener.captureTntPrime(legacy);
        explosionListener.finalizeTntPrime(legacy);
        tntListener.finalizeTntPrime(legacy);

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(
            PaperExplosionBlockChangeListener.EVENT_TYPE,
            api.submissions.remove().eventType()
        );
        Assertions.assertEquals(0, explosionListener.inFlightCount());
        Assertions.assertEquals(0, tntListener.inFlightCount());
    }

    @Test
    void testDragonForeignAirEntryIsSkippedBeforeDragonWorldNormalization() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var foreignWorld = PaperBlockEventTestSupport.world();
        Mockito.when(foreignWorld.getKey()).thenReturn(new NamespacedKey("example", "foreign"));

        var dragonStone = PaperBlockEventTestSupport.block(
            world, 63, 64, 63, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        Mockito.when(world.getBlockAt(63, 64, 63)).thenReturn(dragonStone);
        var foreignAir = PaperBlockEventTestSupport.block(
            foreignWorld, 63, 64, 63, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var blocks = new ArrayList<Block>();
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);
        var explosion = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(explosion.getEntity()).thenReturn(dragon);
        Mockito.when(explosion.getLocation()).thenReturn(new Location(world, 63.5, 64.5, 63.5));
        Mockito.when(explosion.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(explosion.getYield()).thenReturn(1.0F);
        Mockito.when(explosion.blockList()).thenReturn(blocks);

        listener.capture(explosion);
        blocks.add(foreignAir);
        listener.finalizeEvent(explosion);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
        Mockito.verify(world, Mockito.never()).getBlockAt(63, 64, 63);
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testDragonForeignTntAcceptedExpiresFollowingDragonWorldTnt() {
        assertForeignAcceptedExpiresDragonWorldCandidate(Material.TNT, Blocks.TNT.defaultBlockState());
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testDragonForeignStoneAcceptedExpiresFollowingDragonWorldTnt() {
        assertForeignAcceptedExpiresDragonWorldCandidate(
            Material.STONE,
            Blocks.STONE.defaultBlockState()
        );
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static void assertForeignAcceptedExpiresDragonWorldCandidate(
        Material foreignMaterial,
        net.minecraft.world.level.block.state.BlockState foreignState
    ) {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var explosionListener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var tntListener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var foreignWorld = PaperBlockEventTestSupport.world();
        Mockito.when(foreignWorld.getKey()).thenReturn(new NamespacedKey("example", "foreign"));
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);

        var dragonTnt = PaperBlockEventTestSupport.block(
            world, 67, 64, 67, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        Mockito.when(world.getBlockAt(67, 64, 67)).thenReturn(dragonTnt);
        var foreign = PaperBlockEventTestSupport.block(
            foreignWorld, 67, 64, 67, foreignState, foreignMaterial
        );
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);
        var explosion = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(explosion.getEntity()).thenReturn(dragon);
        Mockito.when(explosion.getLocation()).thenReturn(new Location(world, 67.5, 64.5, 67.5));
        Mockito.when(explosion.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(explosion.getYield()).thenReturn(1.0F);
        Mockito.when(explosion.blockList()).thenReturn(
            new ArrayList<>(List.of(foreign, dragonTnt))
        );

        explosionListener.capture(explosion);
        explosionListener.finalizeEvent(explosion);
        Assertions.assertEquals(2, explosionListener.inFlightCount());

        var legacy = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(legacy.getBlock()).thenReturn(dragonTnt);
        Mockito.when(legacy.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION);
        Mockito.when(legacy.getPrimerEntity()).thenReturn(dragon);

        explosionListener.captureTntPrime(legacy);
        tntListener.captureTntPrime(legacy);
        tntListener.finalizeTntPrime(legacy);
        explosionListener.finalizeTntPrime(legacy);

        Assertions.assertEquals(0, explosionListener.inFlightCount());
        Assertions.assertEquals(0, tntListener.inFlightCount());
        Assertions.assertEquals(
            1,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(
                    PaperExplosionBlockChangeListener.EVENT_TYPE
                ))
                .count()
        );
        Assertions.assertEquals(
            foreignMaterial == Material.TNT ? 1 : 0,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(PaperTntPrimeListener.EVENT_TYPE))
                .count()
        );
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testDragonWorldDuplicateTntAcceptedFirstPrimeExpiresDuplicate() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var explosionListener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var tntListener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);
        var tnt = PaperBlockEventTestSupport.block(
            world, 65, 64, 65, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);
        var explosion = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(explosion.getEntity()).thenReturn(dragon);
        Mockito.when(explosion.getLocation()).thenReturn(new Location(world, 65.5, 64.5, 65.5));
        Mockito.when(explosion.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(explosion.getYield()).thenReturn(1.0F);
        Mockito.when(explosion.blockList()).thenReturn(
            new ArrayList<>(List.of(tnt, tnt))
        );

        explosionListener.capture(explosion);
        explosionListener.finalizeEvent(explosion);

        Assertions.assertEquals(2, explosionListener.inFlightCount());

        var legacy = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(legacy.getBlock()).thenReturn(tnt);
        Mockito.when(legacy.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION);
        Mockito.when(legacy.getPrimerEntity()).thenReturn(dragon);

        explosionListener.captureTntPrime(legacy);
        tntListener.captureTntPrime(legacy);
        tntListener.finalizeTntPrime(legacy);
        explosionListener.finalizeTntPrime(legacy);

        Assertions.assertEquals(2, api.submissions.size());
        Assertions.assertEquals(
            1,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(
                    PaperExplosionBlockChangeListener.EVENT_TYPE
                ))
                .count()
        );
        Assertions.assertEquals(
            1,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(PaperTntPrimeListener.EVENT_TYPE))
                .count()
        );
        Assertions.assertEquals(0, explosionListener.inFlightCount());
        Assertions.assertEquals(0, tntListener.inFlightCount());
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testDragonWorldDuplicateTntCancelledThenAcceptedRemainsCorrelated() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var explosionListener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var tntListener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);
        var tnt = PaperBlockEventTestSupport.block(
            world, 66, 64, 66, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);
        var explosion = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(explosion.getEntity()).thenReturn(dragon);
        Mockito.when(explosion.getLocation()).thenReturn(new Location(world, 66.5, 64.5, 66.5));
        Mockito.when(explosion.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(explosion.getYield()).thenReturn(1.0F);
        Mockito.when(explosion.blockList()).thenReturn(
            new ArrayList<>(List.of(tnt, tnt))
        );

        explosionListener.capture(explosion);
        explosionListener.finalizeEvent(explosion);

        var first = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(first.getBlock()).thenReturn(tnt);
        Mockito.when(first.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION);
        Mockito.when(first.getPrimerEntity()).thenReturn(dragon);
        Mockito.when(first.isCancelled()).thenReturn(true);

        explosionListener.captureTntPrime(first);
        tntListener.captureTntPrime(first);
        explosionListener.finalizeTntPrime(first);
        tntListener.finalizeTntPrime(first);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, explosionListener.inFlightCount());

        var second = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(second.getBlock()).thenReturn(tnt);
        Mockito.when(second.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION);
        Mockito.when(second.getPrimerEntity()).thenReturn(dragon);

        explosionListener.captureTntPrime(second);
        tntListener.captureTntPrime(second);
        tntListener.finalizeTntPrime(second);
        explosionListener.finalizeTntPrime(second);

        Assertions.assertEquals(2, api.submissions.size());
        Assertions.assertEquals(0, explosionListener.inFlightCount());
        Assertions.assertEquals(0, tntListener.inFlightCount());
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testDragonLegacyRawEntriesPreserveSameCoordinateTypeOrder() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var explosionListener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var tntListener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var foreignWorld = PaperBlockEventTestSupport.world();
        Mockito.when(foreignWorld.getKey()).thenReturn(new NamespacedKey("example", "foreign"));
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);

        var dragonStone = PaperBlockEventTestSupport.block(
            world, 64, 64, 64, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        Mockito.when(world.getBlockAt(64, 64, 64)).thenReturn(dragonStone);
        var foreignStone = PaperBlockEventTestSupport.block(
            foreignWorld, 64, 64, 64, Blocks.STONE.defaultBlockState(), Material.STONE
        );
        var foreignTnt = PaperBlockEventTestSupport.block(
            foreignWorld, 64, 64, 64, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var blocks = new ArrayList<Block>();
        var dragon = Mockito.mock(EnderDragon.class);
        Mockito.when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(dragon.getType()).thenReturn(EntityType.ENDER_DRAGON);
        var explosion = Mockito.mock(EntityExplodeEvent.class);
        Mockito.when(explosion.getEntity()).thenReturn(dragon);
        Mockito.when(explosion.getLocation()).thenReturn(new Location(world, 64.5, 64.5, 64.5));
        Mockito.when(explosion.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(explosion.getYield()).thenReturn(1.0F);
        Mockito.when(explosion.blockList()).thenReturn(blocks);

        explosionListener.capture(explosion);
        blocks.add(foreignStone);
        blocks.add(foreignTnt);
        explosionListener.finalizeEvent(explosion);

        Assertions.assertEquals(2, explosionListener.inFlightCount());

        var first = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(first.getBlock()).thenReturn(dragonStone);
        Mockito.when(first.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION);
        Mockito.when(first.getPrimerEntity()).thenReturn(dragon);
        Mockito.when(first.isCancelled()).thenReturn(true);

        explosionListener.captureTntPrime(first);
        tntListener.captureTntPrime(first);
        explosionListener.finalizeTntPrime(first);
        tntListener.finalizeTntPrime(first);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, explosionListener.inFlightCount());

        var second = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(second.getBlock()).thenReturn(dragonStone);
        Mockito.when(second.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.EXPLOSION);
        Mockito.when(second.getPrimerEntity()).thenReturn(dragon);

        explosionListener.captureTntPrime(second);
        tntListener.captureTntPrime(second);
        tntListener.finalizeTntPrime(second);
        explosionListener.finalizeTntPrime(second);

        Assertions.assertEquals(2, api.submissions.size());
        Assertions.assertEquals(
            1,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(
                    PaperExplosionBlockChangeListener.EVENT_TYPE
                ))
                .count()
        );
        Assertions.assertEquals(
            1,
            api.submissions.stream()
                .filter(submission -> submission.eventType().equals(PaperTntPrimeListener.EVENT_TYPE))
                .count()
        );
        Assertions.assertEquals(0, explosionListener.inFlightCount());
    }

    @Test
    void testForeignWorldFinalEntryIsNormalizedToExplosionWorld() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperExplosionBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var foreignWorld = PaperBlockEventTestSupport.world();
        Mockito.when(foreignWorld.getKey()).thenReturn(new NamespacedKey("example", "foreign"));

        var sourceBlock = PaperBlockEventTestSupport.block(
            world, 57, 64, 56, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var sourceState = PaperBlockEventTestSupport.state(
            world, sourceBlock, 57, 64, 56, Blocks.RESPAWN_ANCHOR.defaultBlockState()
        );
        var normalized = PaperBlockEventTestSupport.block(
            world, 57, 64, 57, Blocks.GOLD_BLOCK.defaultBlockState(), Material.GOLD_BLOCK
        );
        Mockito.when(world.getBlockAt(57, 64, 57)).thenReturn(normalized);
        var foreign = PaperBlockEventTestSupport.block(
            foreignWorld, 57, 64, 57, Blocks.DIAMOND_BLOCK.defaultBlockState(), Material.DIAMOND_BLOCK
        );
        var blocks = new ArrayList<Block>();
        var event = Mockito.mock(BlockExplodeEvent.class);
        Mockito.when(event.getExplodedBlockState()).thenReturn(sourceState);
        Mockito.when(event.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        Mockito.when(event.blockList()).thenReturn(blocks);

        listener.capture(event);
        blocks.add(foreign);
        listener.finalizeEvent(event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(new BlockPosition(57, 64, 57), submission.position());
        Assertions.assertEquals(
            PaperKansokusha.key(world.getKey()),
            submission.worldKey()
        );
        var payload = PaperWorldMutationPayloadCodec.decode(submission.payload());
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(
                Blocks.GOLD_BLOCK.defaultBlockState().asBlockData()
            ),
            payload.get("pre_state")
        );
        Mockito.verify(foreign, Mockito.never()).getBlockData();
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
