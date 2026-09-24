package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.TNTPrimeEvent;
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

class PaperTntPrimeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testPrimeCapturesCauseActorAndPrimingBlockImmutably() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var tnt = PaperBlockEventTestSupport.block(
            world, 10, 64, 20, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var priming = PaperBlockEventTestSupport.block(
            world, 9, 64, 20, Blocks.REDSTONE_BLOCK.defaultBlockState(), Material.REDSTONE_BLOCK
        );
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);
        var event = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(tnt);
        Mockito.when(event.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.PLAYER);
        Mockito.when(event.getPrimingEntity()).thenReturn(player);
        Mockito.when(event.getPrimingBlock()).thenReturn(priming);

        listener.capture(event);
        Mockito.when(priming.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());
        listener.finalizeEvent(event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperTntPrimeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(10, 64, 20), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());

        var payload = PaperWorldMutationPayloadCodec.decode(submission.payload());
        Assertions.assertEquals("player", payload.getString("cause").orElseThrow());
        Assertions.assertEquals(
            PLAYER_ID.toString(),
            payload.getString("actor_entity_uuid").orElseThrow()
        );
        Assertions.assertEquals("PLAYER", payload.getString("actor_entity_type").orElseThrow());
        Assertions.assertEquals(
            PaperBlockEventTestSupport.position(new BlockPosition(9, 64, 20)),
            payload.get("priming_block")
        );
        Assertions.assertEquals(
            PaperBlockStatePayloadCodec.blockState(
                Blocks.REDSTONE_BLOCK.defaultBlockState().asBlockData()
            ),
            payload.get("priming_block_state")
        );
        Assertions.assertFalse(payload.contains("explosion_result"));
        Assertions.assertEquals(0, listener.inFlightCount());
        Mockito.verify(priming, Mockito.times(1)).getBlockData();
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testFirePrimeWaitsForLegacyPaperGate() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperTntPrimeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(true);
        var tnt = PaperBlockEventTestSupport.block(
            world, 30, 64, 30, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var modern = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(modern.getBlock()).thenReturn(tnt);
        Mockito.when(modern.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.FIRE);

        listener.capture(modern);
        listener.finalizeEvent(modern);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, listener.inFlightCount());

        var legacy = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(legacy.getBlock()).thenReturn(tnt);
        Mockito.when(legacy.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.FIRE);
        listener.finalizeTntPrime(legacy);

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testCancelledLegacyFirePrimeDropsPendingPrime() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperTntPrimeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var tnt = PaperBlockEventTestSupport.block(
            world, 31, 64, 31, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var modern = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(modern.getBlock()).thenReturn(tnt);
        Mockito.when(modern.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.FIRE);

        listener.capture(modern);
        listener.finalizeEvent(modern);

        var legacy = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(legacy.getBlock()).thenReturn(tnt);
        Mockito.when(legacy.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.FIRE);
        Mockito.when(legacy.isCancelled()).thenReturn(true);
        listener.finalizeTntPrime(legacy);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testAcceptedLegacyFireDoesNotSubmitWhenTntExplodesIsFalse() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperTntPrimeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(false);
        var tnt = PaperBlockEventTestSupport.block(
            world, 32, 64, 32, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var modern = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(modern.getBlock()).thenReturn(tnt);
        Mockito.when(modern.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.FIRE);

        listener.capture(modern);
        listener.finalizeEvent(modern);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, listener.inFlightCount());

        var legacy = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(legacy.getBlock()).thenReturn(tnt);
        Mockito.when(legacy.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.FIRE);
        listener.finalizeTntPrime(legacy);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testCancelledPrimeDoesNotSubmit() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperTntPrimeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var tnt = PaperBlockEventTestSupport.block(
            world, 1, 2, 3, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var event = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(tnt);
        Mockito.when(event.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.REDSTONE);
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testConcurrentFoliaStylePrimesDoNotCrossSnapshots() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperTntPrimeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var events = new ArrayList<TNTPrimeEvent>();
        var expectedActors = new HashMap<Integer, UUID>();

        for (int i = 0; i < 32; i++) {
            var x = 1000 + i;
            var actorId = UUID.nameUUIDFromBytes(("actor-" + i).getBytes(StandardCharsets.UTF_8));
            expectedActors.put(x, actorId);
            var tnt = PaperBlockEventTestSupport.block(
                world, x, 70, -i, Blocks.TNT.defaultBlockState(), Material.TNT
            );
            var actor = Mockito.mock(Entity.class);
            Mockito.when(actor.getUniqueId()).thenReturn(actorId);
            Mockito.when(actor.getType()).thenReturn(EntityType.CREEPER);
            var event = Mockito.mock(TNTPrimeEvent.class);
            Mockito.when(event.getBlock()).thenReturn(tnt);
            Mockito.when(event.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.EXPLOSION);
            Mockito.when(event.getPrimingEntity()).thenReturn(actor);
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

        Assertions.assertEquals(32, api.submissions.size());
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
