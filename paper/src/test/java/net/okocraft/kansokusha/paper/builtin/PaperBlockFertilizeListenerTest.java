package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class PaperBlockFertilizeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174001");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testPlayerFertilizationRecordsPreAndPostState() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockFertilizeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var source = PaperBlockEventTestSupport.block(
            world, 10, 64, 10, Blocks.OAK_SAPLING.defaultBlockState(), Material.OAK_SAPLING
        );
        var cocoa = PaperBlockEventTestSupport.block(
            world, 11, 64, 10, Blocks.COCOA.defaultBlockState(), Material.COCOA
        );
        var grownState = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 1);
        var changed = PaperBlockEventTestSupport.state(
            world, cocoa, 11, 64, 10, grownState
        );
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);

        var event = Mockito.mock(BlockFertilizeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(source);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getBlocks()).thenReturn(List.of(changed));

        PaperListenerTestSupport.fire(listener, event);

        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperBlockFertilizeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(11, 64, 10), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        Assertions.assertEquals(
            fertilizePayload(
                Blocks.COCOA.defaultBlockState(),
                grownState,
                new BlockPosition(10, 64, 10)
            ),
            PaperPayloadNbtCodec.decode(submission.payload())
        );
    }

    @Test
    void testDuplicatePositionsUseLastPostState() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockFertilizeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var source = PaperBlockEventTestSupport.block(
            world, 30, 64, 30, Blocks.OAK_SAPLING.defaultBlockState(), Material.OAK_SAPLING
        );
        var changedBlock = PaperBlockEventTestSupport.block(
            world, 31, 64, 30, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var first = PaperBlockEventTestSupport.state(
            world, changedBlock, 31, 64, 30, Blocks.OAK_LOG.defaultBlockState()
        );
        var last = PaperBlockEventTestSupport.state(
            world, changedBlock, 31, 64, 30, Blocks.BIRCH_LOG.defaultBlockState()
        );
        var changedStates = new ArrayList<org.bukkit.block.BlockState>(List.of(first, last));
        var event = Mockito.mock(BlockFertilizeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(source);
        Mockito.when(event.getBlocks()).thenReturn(changedStates);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(new BlockPosition(31, 64, 30), submission.position());
        Assertions.assertEquals(
            fertilizePayload(
                Blocks.AIR.defaultBlockState(),
                Blocks.BIRCH_LOG.defaultBlockState(),
                new BlockPosition(30, 64, 30)
            ),
            PaperPayloadNbtCodec.decode(submission.payload())
        );
    }

    @Test
    void testNetNoOpAfterCoalescingIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockFertilizeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var source = PaperBlockEventTestSupport.block(
            world, 60, 64, 60, Blocks.OAK_SAPLING.defaultBlockState(), Material.OAK_SAPLING
        );
        var target = PaperBlockEventTestSupport.block(
            world, 61, 64, 60, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var first = PaperBlockEventTestSupport.state(
            world, target, 61, 64, 60, Blocks.OAK_LOG.defaultBlockState()
        );
        var last = PaperBlockEventTestSupport.state(
            world, target, 61, 64, 60, Blocks.AIR.defaultBlockState()
        );
        var event = Mockito.mock(BlockFertilizeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(source);
        Mockito.when(event.getBlocks()).thenReturn(List.of(first, last));

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testCancelledFertilizationIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockFertilizeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var source = PaperBlockEventTestSupport.block(
            world, 1, 2, 3, Blocks.OAK_SAPLING.defaultBlockState(), Material.OAK_SAPLING
        );
        var target = PaperBlockEventTestSupport.block(
            world, 2, 2, 3, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var changed = PaperBlockEventTestSupport.state(
            world, target, 2, 2, 3, Blocks.OAK_LOG.defaultBlockState()
        );
        var event = Mockito.mock(BlockFertilizeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(source);
        Mockito.when(event.getBlocks()).thenReturn(List.of(changed));
        Mockito.when(event.isCancelled()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Mockito.verify(changed, Mockito.never()).getBlockData();
    }

    private static CompoundTag fertilizePayload(
        net.minecraft.world.level.block.state.BlockState preState,
        net.minecraft.world.level.block.state.BlockState postState,
        BlockPosition sourcePosition
    ) {
        var payload = new CompoundTag();
        payload.put("pre_state", NbtUtils.writeBlockState(preState));
        payload.put("post_state", NbtUtils.writeBlockState(postState));
        payload.putString("source_event", PaperBlockFertilizeListener.SOURCE_EVENT);
        payload.put("source", PaperBlockEventTestSupport.position(sourcePosition));
        return payload;
    }
}
