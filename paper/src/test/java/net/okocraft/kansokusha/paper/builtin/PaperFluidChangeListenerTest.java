package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Material;
import org.bukkit.event.block.BlockFromToEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class PaperFluidChangeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testWaterArrivalUsesOnlyKindAndPositions() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperFluidChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var source = PaperBlockEventTestSupport.block(
            world, 10, 62, 10, Blocks.WATER.defaultBlockState(), Material.WATER
        );
        var destination = PaperBlockEventTestSupport.block(
            world, 11, 62, 10, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var event = Mockito.mock(BlockFromToEvent.class);
        Mockito.when(event.getBlock()).thenReturn(source);
        Mockito.when(event.getToBlock()).thenReturn(destination);

        listener.capture(event);
        Mockito.when(source.getType()).thenReturn(Material.LAVA);
        Mockito.when(source.getX()).thenReturn(1000);
        Mockito.when(destination.getX()).thenReturn(2000);
        listener.finalizeEvent(event);

        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperFluidChangeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(11, 62, 10), submission.position());
        Assertions.assertNull(submission.subject());

        var expected = new CompoundTag();
        expected.putString("fluid", "water");
        expected.put(
            "source",
            PaperBlockEventTestSupport.position(new BlockPosition(10, 62, 10))
        );
        expected.put(
            "destination",
            PaperBlockEventTestSupport.position(new BlockPosition(11, 62, 10))
        );
        Assertions.assertEquals(expected, PaperBlockStatePayloadCodec.decode(submission.payload()));
        Mockito.verify(source, Mockito.never()).getBlockData();
        Mockito.verify(destination, Mockito.never()).getBlockData();
    }

    @Test
    void testLavaArrivalIsCaptured() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperFluidChangeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var source = PaperBlockEventTestSupport.block(
            world, 1, 2, 3, Blocks.LAVA.defaultBlockState(), Material.LAVA
        );
        var destination = PaperBlockEventTestSupport.block(
            world, 2, 2, 3, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var event = Mockito.mock(BlockFromToEvent.class);
        Mockito.when(event.getBlock()).thenReturn(source);
        Mockito.when(event.getToBlock()).thenReturn(destination);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertEquals(1, api.submissions.size());
    }

    @Test
    void testCancelledAndNonFluidMovesAreDropped() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperFluidChangeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();

        var cancelledSource = PaperBlockEventTestSupport.block(
            world, 1, 2, 3, Blocks.WATER.defaultBlockState(), Material.WATER
        );
        var cancelledDestination = PaperBlockEventTestSupport.block(
            world, 2, 2, 3, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var cancelled = Mockito.mock(BlockFromToEvent.class);
        Mockito.when(cancelled.getBlock()).thenReturn(cancelledSource);
        Mockito.when(cancelled.getToBlock()).thenReturn(cancelledDestination);
        Mockito.when(cancelled.isCancelled()).thenReturn(true);
        listener.capture(cancelled);
        listener.finalizeEvent(cancelled);

        var dragonEggBlock = PaperBlockEventTestSupport.block(
            world, 4, 5, 6, Blocks.DRAGON_EGG.defaultBlockState(), Material.DRAGON_EGG
        );
        var dragonEgg = Mockito.mock(BlockFromToEvent.class);
        Mockito.when(dragonEgg.getBlock()).thenReturn(dragonEggBlock);
        listener.capture(dragonEgg);
        listener.finalizeEvent(dragonEgg);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
        Mockito.verify(dragonEgg, Mockito.never()).getToBlock();
    }
}
