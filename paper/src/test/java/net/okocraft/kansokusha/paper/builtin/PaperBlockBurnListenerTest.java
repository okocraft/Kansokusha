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
        expected.put("pre_state", PaperBlockStatePayloadCodec.blockState(Blocks.OAK_PLANKS.defaultBlockState().asBlockData()));
        expected.put("source", PaperBlockEventTestSupport.position(new BlockPosition(19, 65, 30)));
        expected.put("source_state", PaperBlockStatePayloadCodec.blockState(Blocks.FIRE.defaultBlockState().asBlockData()));
        Assertions.assertEquals(expected, PaperBlockStatePayloadCodec.decode(submission.payload()));
        Mockito.verify(burned, Mockito.times(1)).getBlockData();
        Mockito.verify(source, Mockito.times(1)).getBlockData();
    }

    @Test
    void testCancelledBurnDropsSnapshot() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockBurnListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var event = Mockito.mock(BlockBurnEvent.class);
        Mockito.when(event.getBlock()).thenReturn(PaperBlockEventTestSupport.block(
            PaperBlockEventTestSupport.world(), 1, 2, 3, Blocks.OAK_LOG.defaultBlockState(), Material.OAK_LOG
        ));
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }
}
