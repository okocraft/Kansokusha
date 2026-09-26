package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.TNTPrimeEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class PaperTntPrimeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testPrimeRecordsCauseActorAndPrimingBlock() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperTntPrimeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var tnt = tnt(true);
        var priming = PaperBlockEventTestSupport.block(
            tnt.getWorld(), 9, 64, 20, Blocks.REDSTONE_BLOCK.defaultBlockState(), Material.REDSTONE_BLOCK
        );
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);
        var event = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(tnt);
        Mockito.when(event.getCause()).thenReturn(TNTPrimeEvent.PrimeCause.PLAYER);
        Mockito.when(event.getPrimingEntity()).thenReturn(player);
        Mockito.when(event.getPrimingBlock()).thenReturn(priming);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperTntPrimeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(10, 64, 20), submission.position());
        Assertions.assertEquals(new PlayerActor(PLAYER_ID), submission.actor());
        Assertions.assertEquals(Key.key("minecraft", "tnt"), submission.targetType());

        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("player", payload.getString("cause").orElseThrow());
        Assertions.assertFalse(payload.contains("actor_entity_uuid"));
        Assertions.assertFalse(payload.contains("actor_entity_type"));
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
    }

    @Test
    void testFireAndExplosionPrimesAreRecordedIndependently() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperTntPrimeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);

        PaperListenerTestSupport.fire(listener, primeEvent(tnt(true), TNTPrimeEvent.PrimeCause.FIRE));
        PaperListenerTestSupport.fire(
            listener,
            primeEvent(tnt(true), TNTPrimeEvent.PrimeCause.EXPLOSION)
        );

        Assertions.assertEquals(2, api.submissions.size());
    }

    @Test
    void testPrimeIsNotRecordedWhenTntDoesNotExplode() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperTntPrimeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);

        PaperListenerTestSupport.fire(listener, primeEvent(tnt(false), TNTPrimeEvent.PrimeCause.FIRE));

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testCancelledPrimeIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperTntPrimeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var event = primeEvent(tnt(true), TNTPrimeEvent.PrimeCause.REDSTONE);
        Mockito.when(event.isCancelled()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static Block tnt(boolean tntExplodes) {
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(tntExplodes);
        return PaperBlockEventTestSupport.block(
            world, 10, 64, 20, Blocks.TNT.defaultBlockState(), Material.TNT
        );
    }

    private static TNTPrimeEvent primeEvent(Block tnt, TNTPrimeEvent.PrimeCause cause) {
        var event = Mockito.mock(TNTPrimeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(tnt);
        Mockito.when(event.getCause()).thenReturn(cause);
        return event;
    }
}
