package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Material;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

class PaperSpongeAbsorbListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testFinalAffectedBlocksShareOccurredAtAndKeepLowestPreState() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperSpongeAbsorbListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var sponge = PaperBlockEventTestSupport.block(
            world, 10, 64, 10, Blocks.SPONGE.defaultBlockState(), Material.SPONGE
        );

        var water = PaperBlockEventTestSupport.block(
            world, 11, 64, 10, Blocks.WATER.defaultBlockState(), Material.WATER
        );
        var clearedWater = PaperBlockEventTestSupport.state(
            world, water, 11, 64, 10, Blocks.AIR.defaultBlockState()
        );

        var wetSlabState = Blocks.OAK_SLAB.defaultBlockState().setValue(
            BlockStateProperties.WATERLOGGED,
            true
        );
        var drySlabState = Blocks.OAK_SLAB.defaultBlockState().setValue(
            BlockStateProperties.WATERLOGGED,
            false
        );
        var slab = PaperBlockEventTestSupport.block(
            world, 12, 64, 10, wetSlabState, Material.OAK_SLAB
        );
        var clearedSlab = PaperBlockEventTestSupport.state(
            world, slab, 12, 64, 10, drySlabState
        );

        var changedStates = new ArrayList<org.bukkit.block.BlockState>(
            List.of(clearedWater, clearedSlab)
        );
        var event = Mockito.mock(SpongeAbsorbEvent.class);
        Mockito.when(event.getBlock()).thenReturn(sponge);
        Mockito.when(event.getBlocks()).thenReturn(changedStates);

        listener.capture(event);

        Mockito.when(water.getBlockData()).thenReturn(Blocks.LAVA.defaultBlockState().asBlockData());
        Mockito.when(slab.getBlockData()).thenReturn(Blocks.STONE.defaultBlockState().asBlockData());

        listener.finalizeEvent(event);

        Assertions.assertEquals(2, api.submissions.size());
        Assertions.assertEquals(0, listener.inFlightCount());

        var first = api.submissions.stream()
            .filter(submission -> new BlockPosition(11, 64, 10).equals(submission.position()))
            .findFirst()
            .orElseThrow();
        var second = api.submissions.stream()
            .filter(submission -> new BlockPosition(12, 64, 10).equals(submission.position()))
            .findFirst()
            .orElseThrow();

        Assertions.assertEquals(PaperSpongeAbsorbListener.EVENT_TYPE, first.eventType());
        Assertions.assertEquals(OCCURRED_AT, first.occurredAt());
        Assertions.assertEquals(OCCURRED_AT, second.occurredAt());
        Assertions.assertNull(first.subject());
        Assertions.assertNull(second.subject());
        Assertions.assertEquals(
            spongePayload(
                Blocks.WATER.defaultBlockState(),
                new BlockPosition(10, 64, 10)
            ),
            PaperPayloadNbtCodec.decode(first.payload())
        );
        Assertions.assertEquals(
            spongePayload(wetSlabState, new BlockPosition(10, 64, 10)),
            PaperPayloadNbtCodec.decode(second.payload())
        );

        Mockito.verify(water, Mockito.times(1)).getBlockData();
        Mockito.verify(slab, Mockito.times(1)).getBlockData();
    }

    @Test
    void testRemovedAffectedBlockIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperSpongeAbsorbListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var sponge = PaperBlockEventTestSupport.block(
            world, 20, 64, 20, Blocks.SPONGE.defaultBlockState(), Material.SPONGE
        );
        var water = PaperBlockEventTestSupport.block(
            world, 21, 64, 20, Blocks.WATER.defaultBlockState(), Material.WATER
        );
        var cleared = PaperBlockEventTestSupport.state(
            world, water, 21, 64, 20, Blocks.AIR.defaultBlockState()
        );
        var changedStates = new ArrayList<org.bukkit.block.BlockState>();
        changedStates.add(cleared);
        var event = Mockito.mock(SpongeAbsorbEvent.class);
        Mockito.when(event.getBlock()).thenReturn(sponge);
        Mockito.when(event.getBlocks()).thenReturn(changedStates);

        listener.capture(event);
        changedStates.clear();
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
        Mockito.verify(water, Mockito.times(1)).getBlockData();
    }

    @Test
    void testAddedAffectedBlockUsesMonitorLivePreState() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperSpongeAbsorbListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var sponge = PaperBlockEventTestSupport.block(
            world, 30, 64, 30, Blocks.SPONGE.defaultBlockState(), Material.SPONGE
        );
        var water = PaperBlockEventTestSupport.block(
            world, 31, 64, 30, Blocks.WATER.defaultBlockState(), Material.WATER
        );
        var cleared = PaperBlockEventTestSupport.state(
            world, water, 31, 64, 30, Blocks.AIR.defaultBlockState()
        );
        var changedStates = new ArrayList<org.bukkit.block.BlockState>();
        var event = Mockito.mock(SpongeAbsorbEvent.class);
        Mockito.when(event.getBlock()).thenReturn(sponge);
        Mockito.when(event.getBlocks()).thenReturn(changedStates);

        listener.capture(event);
        changedStates.add(cleared);
        listener.finalizeEvent(event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(31, 64, 30), submission.position());
        Assertions.assertEquals(
            spongePayload(
                Blocks.WATER.defaultBlockState(),
                new BlockPosition(30, 64, 30)
            ),
            PaperPayloadNbtCodec.decode(submission.payload())
        );
        Mockito.verify(water, Mockito.times(1)).getBlockData();
    }

    @Test
    void testCancelledAbsorbDropsAllCapturedBlocks() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperSpongeAbsorbListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var sponge = PaperBlockEventTestSupport.block(
            world, 1, 2, 3, Blocks.SPONGE.defaultBlockState(), Material.SPONGE
        );
        var water = PaperBlockEventTestSupport.block(
            world, 2, 2, 3, Blocks.WATER.defaultBlockState(), Material.WATER
        );
        var cleared = PaperBlockEventTestSupport.state(
            world, water, 2, 2, 3, Blocks.AIR.defaultBlockState()
        );
        var event = Mockito.mock(SpongeAbsorbEvent.class);
        Mockito.when(event.getBlock()).thenReturn(sponge);
        Mockito.when(event.getBlocks()).thenReturn(List.of(cleared));
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static CompoundTag spongePayload(
        net.minecraft.world.level.block.state.BlockState preState,
        BlockPosition spongeOrigin
    ) {
        var payload = new CompoundTag();
        payload.put("pre_state", NbtUtils.writeBlockState(preState));
        payload.put("sponge_origin", PaperBlockEventTestSupport.position(spongeOrigin));
        return payload;
    }
}
