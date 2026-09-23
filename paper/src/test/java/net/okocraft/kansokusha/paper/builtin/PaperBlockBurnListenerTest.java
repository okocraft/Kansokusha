package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Material;
import org.bukkit.event.block.BlockBurnEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class PaperBlockBurnListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testCapturesBurnedAndSourcePreStatesImmutably() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockBurnListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var burned = PaperBlockEventTestSupport.block(
            world, 20, 65, 30, Blocks.OAK_PLANKS.defaultBlockState(), Material.OAK_PLANKS
        );
        var source = PaperBlockEventTestSupport.block(
            world, 19, 65, 30, Blocks.FIRE.defaultBlockState(), Material.FIRE
        );
        var event = Mockito.mock(BlockBurnEvent.class);
        Mockito.when(event.getBlock()).thenReturn(burned);
        Mockito.when(event.getIgnitingBlock()).thenReturn(source);

        listener.capture(event);
        Mockito.when(burned.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());
        Mockito.when(source.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());
        listener.finalizeEvent(event);

        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperBlockBurnListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(20, 65, 30), submission.position());
        Assertions.assertNull(submission.subject());

        var expected = new CompoundTag();
        expected.put(
            "pre_state",
            PaperBlockStatePayloadCodec.blockState(Blocks.OAK_PLANKS.defaultBlockState().asBlockData())
        );
        expected.put(
            "source",
            PaperBlockEventTestSupport.position(new BlockPosition(19, 65, 30))
        );
        expected.put(
            "source_state",
            PaperBlockStatePayloadCodec.blockState(Blocks.FIRE.defaultBlockState().asBlockData())
        );
        Assertions.assertEquals(expected, PaperBlockStatePayloadCodec.decode(submission.payload()));
        Mockito.verify(burned, Mockito.times(1)).getBlockData();
        Mockito.verify(source, Mockito.times(1)).getBlockData();
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testTntBurnWaitsForCompletePrimeChain() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockBurnListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var burned = PaperBlockEventTestSupport.block(
            world, 7, 70, 7, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var burn = Mockito.mock(BlockBurnEvent.class);
        Mockito.when(burn.getBlock()).thenReturn(burned);

        listener.capture(burn);
        listener.finalizeEvent(burn);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, listener.inFlightCount());

        var bukkitPrime = Mockito.mock(org.bukkit.event.block.TNTPrimeEvent.class);
        Mockito.when(bukkitPrime.getBlock()).thenReturn(burned);
        Mockito.when(bukkitPrime.getCause())
            .thenReturn(org.bukkit.event.block.TNTPrimeEvent.PrimeCause.FIRE);
        listener.finalizeTntPrime(bukkitPrime);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, listener.inFlightCount());

        var paperPrime = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(paperPrime.getBlock()).thenReturn(burned);
        Mockito.when(paperPrime.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.FIRE);
        listener.finalizeTntPrime(paperPrime);

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testCancelledBukkitTntPrimeDropsPendingBurn() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockBurnListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var burned = PaperBlockEventTestSupport.block(
            world, 7, 70, 7, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var burn = Mockito.mock(BlockBurnEvent.class);
        Mockito.when(burn.getBlock()).thenReturn(burned);
        listener.capture(burn);
        listener.finalizeEvent(burn);

        var prime = Mockito.mock(org.bukkit.event.block.TNTPrimeEvent.class);
        Mockito.when(prime.getBlock()).thenReturn(burned);
        Mockito.when(prime.getCause())
            .thenReturn(org.bukkit.event.block.TNTPrimeEvent.PrimeCause.FIRE);
        Mockito.when(prime.isCancelled()).thenReturn(true);
        listener.finalizeTntPrime(prime);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void testCancelledPaperTntPrimeDropsPendingBurn() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockBurnListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var burned = PaperBlockEventTestSupport.block(
            world, 7, 70, 7, Blocks.TNT.defaultBlockState(), Material.TNT
        );
        var burn = Mockito.mock(BlockBurnEvent.class);
        Mockito.when(burn.getBlock()).thenReturn(burned);
        listener.capture(burn);
        listener.finalizeEvent(burn);

        var bukkitPrime = Mockito.mock(org.bukkit.event.block.TNTPrimeEvent.class);
        Mockito.when(bukkitPrime.getBlock()).thenReturn(burned);
        Mockito.when(bukkitPrime.getCause())
            .thenReturn(org.bukkit.event.block.TNTPrimeEvent.PrimeCause.FIRE);
        listener.finalizeTntPrime(bukkitPrime);

        var paperPrime = Mockito.mock(com.destroystokyo.paper.event.block.TNTPrimeEvent.class);
        Mockito.when(paperPrime.getBlock()).thenReturn(burned);
        Mockito.when(paperPrime.getReason())
            .thenReturn(com.destroystokyo.paper.event.block.TNTPrimeEvent.PrimeReason.FIRE);
        Mockito.when(paperPrime.isCancelled()).thenReturn(true);
        listener.finalizeTntPrime(paperPrime);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testCancelledBurnDropsSnapshot() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockBurnListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var burned = PaperBlockEventTestSupport.block(
            world, 1, 2, 3, Blocks.OAK_LOG.defaultBlockState(), Material.OAK_LOG
        );
        var event = Mockito.mock(BlockBurnEvent.class);
        Mockito.when(event.getBlock()).thenReturn(burned);
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }
}
