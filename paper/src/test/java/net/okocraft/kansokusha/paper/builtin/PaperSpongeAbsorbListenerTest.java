package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.okocraft.kansokusha.api.actor.BlockActor;
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
    void testAffectedBlocksShareOccurredAtAndRecordPreState() throws Exception {
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

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(2, api.submissions.size());

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
        Assertions.assertEquals(new BlockActor(Key.key("minecraft", "sponge")), first.actor());
        Assertions.assertEquals(new BlockActor(Key.key("minecraft", "sponge")), second.actor());
        Assertions.assertEquals(Key.key("minecraft", "water"), first.targetType());
        Assertions.assertEquals(Key.key("minecraft", "oak_slab"), second.targetType());
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
    }

    @Test
    void testCancelledAbsorbIsNotSubmitted() {
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

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
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
