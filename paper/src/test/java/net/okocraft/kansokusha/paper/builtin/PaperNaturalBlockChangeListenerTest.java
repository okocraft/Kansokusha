package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;

class PaperNaturalBlockChangeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testBlockSpreadRecordsSourceAndCause() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperNaturalBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var source = PaperBlockEventTestSupport.block(
            world, 10, 64, 10, Blocks.FIRE.defaultBlockState(), Material.FIRE
        );
        var event = Mockito.mock(BlockSpreadEvent.class);
        stubTransition(event, world, 11, Blocks.AIR.defaultBlockState(), Blocks.FIRE.defaultBlockState());
        Mockito.when(event.getSource()).thenReturn(source);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperNaturalBlockChangeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(11, 64, 0), submission.position());
        Assertions.assertNull(submission.subject());
        Assertions.assertEquals(
            naturalPayload(
                Blocks.AIR.defaultBlockState(),
                Blocks.FIRE.defaultBlockState(),
                "block_spread",
                "fire",
                new BlockPosition(10, 64, 10)
            ),
            PaperPayloadNbtCodec.decode(submission.payload())
        );
    }

    @Test
    void testSingleBlockSourcesUseCommonSchema() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperNaturalBlockChangeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();

        var fade = Mockito.mock(BlockFadeEvent.class);
        stubTransition(fade, world, 1, Blocks.ICE.defaultBlockState(), Blocks.WATER.defaultBlockState());
        PaperListenerTestSupport.fire(listener, fade);

        var form = Mockito.mock(BlockFormEvent.class);
        stubTransition(form, world, 2, Blocks.WATER.defaultBlockState(), Blocks.ICE.defaultBlockState());
        PaperListenerTestSupport.fire(listener, form);

        var growPreState = Blocks.WHEAT.defaultBlockState();
        var growPostState = growPreState.setValue(CropBlock.AGE, 1);
        var grow = Mockito.mock(BlockGrowEvent.class);
        stubTransition(grow, world, 3, growPreState, growPostState);
        PaperListenerTestSupport.fire(listener, grow);

        var moisturePreState = Blocks.FARMLAND.defaultBlockState();
        var moisturePostState = moisturePreState.setValue(FarmlandBlock.MOISTURE, 1);
        var moisture = Mockito.mock(MoistureChangeEvent.class);
        stubTransition(moisture, world, 4, moisturePreState, moisturePostState);
        PaperListenerTestSupport.fire(listener, moisture);

        var leavesBlock = PaperBlockEventTestSupport.block(
            world, 5, 64, 0, Blocks.OAK_LEAVES.defaultBlockState(), Material.OAK_LEAVES
        );
        var leaves = Mockito.mock(LeavesDecayEvent.class);
        Mockito.when(leaves.getBlock()).thenReturn(leavesBlock);
        PaperListenerTestSupport.fire(listener, leaves);

        var byX = byX(api.submissions);
        Assertions.assertEquals(5, byX.size());
        Assertions.assertEquals(
            naturalPayload(Blocks.ICE.defaultBlockState(), Blocks.WATER.defaultBlockState(), "block_fade", null, null),
            PaperPayloadNbtCodec.decode(byX.get(1).payload())
        );
        Assertions.assertEquals(
            naturalPayload(Blocks.WATER.defaultBlockState(), Blocks.ICE.defaultBlockState(), "block_form", null, null),
            PaperPayloadNbtCodec.decode(byX.get(2).payload())
        );
        Assertions.assertEquals(
            naturalPayload(growPreState, growPostState, "block_grow", null, null),
            PaperPayloadNbtCodec.decode(byX.get(3).payload())
        );
        Assertions.assertEquals(
            naturalPayload(moisturePreState, moisturePostState, "moisture_change", null, null),
            PaperPayloadNbtCodec.decode(byX.get(4).payload())
        );
        Assertions.assertEquals(
            naturalPayload(
                Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                "leaves_decay",
                null,
                null
            ),
            PaperPayloadNbtCodec.decode(byX.get(5).payload())
        );
    }

    @Test
    void testTransitionToSameStateIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperNaturalBlockChangeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var grow = Mockito.mock(BlockGrowEvent.class);
        stubTransition(
            grow,
            PaperBlockEventTestSupport.world(),
            1,
            Blocks.WHEAT.defaultBlockState(),
            Blocks.WHEAT.defaultBlockState()
        );

        PaperListenerTestSupport.fire(listener, grow);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testStructureGrowRecordsEachChangedBlockWithOneTimestamp() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var clock = Mockito.mock(Clock.class);
        Mockito.when(clock.instant()).thenReturn(OCCURRED_AT, OCCURRED_AT.plusSeconds(1));
        var listener = PaperNaturalBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            clock
        );
        var world = PaperBlockEventTestSupport.world();
        var states = new ArrayList<org.bukkit.block.BlockState>();
        for (int i = 0; i < 3; i++) {
            var block = PaperBlockEventTestSupport.block(
                world, 100 + i, 70, 0, Blocks.AIR.defaultBlockState(), Material.AIR
            );
            var post = i < 2 ? Blocks.OAK_LOG.defaultBlockState() : Blocks.AIR.defaultBlockState();
            states.add(PaperBlockEventTestSupport.state(world, block, 100 + i, 70, 0, post));
        }
        var event = Mockito.mock(StructureGrowEvent.class);
        Mockito.when(event.getBlocks()).thenReturn(states);
        Mockito.when(event.getSpecies()).thenReturn(TreeType.BIRCH);

        PaperListenerTestSupport.fire(listener, event);

        var byX = byX(api.submissions);
        Assertions.assertEquals(2, byX.size());
        for (int i = 0; i < 2; i++) {
            var submission = byX.get(100 + i);
            Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
            Assertions.assertEquals(
                naturalPayload(
                    Blocks.AIR.defaultBlockState(),
                    Blocks.OAK_LOG.defaultBlockState(),
                    "structure_grow",
                    "birch",
                    null
                ),
                PaperPayloadNbtCodec.decode(submission.payload())
            );
        }
        Mockito.verify(clock, Mockito.times(1)).instant();
    }

    @Test
    void testBonemealStructureGrowIsLeftToBlockFertilize() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperNaturalBlockChangeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var event = Mockito.mock(StructureGrowEvent.class);
        Mockito.when(event.isFromBonemeal()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Mockito.verify(event, Mockito.never()).getBlocks();
    }

    @Test
    void testFallingScaffoldingFadeIsLeftToEntityBlockChange() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperNaturalBlockChangeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var scaffolding = Blocks.SCAFFOLDING.defaultBlockState();

        var falling = Mockito.mock(BlockFadeEvent.class);
        stubTransition(falling, world, 1, scaffolding.setValue(ScaffoldingBlock.DISTANCE, 7), Blocks.AIR.defaultBlockState());
        PaperListenerTestSupport.fire(listener, falling);
        Assertions.assertTrue(api.submissions.isEmpty());

        var destroyed = Mockito.mock(BlockFadeEvent.class);
        stubTransition(destroyed, world, 2, scaffolding.setValue(ScaffoldingBlock.DISTANCE, 6), Blocks.AIR.defaultBlockState());
        PaperListenerTestSupport.fire(listener, destroyed);
        Assertions.assertEquals(1, api.submissions.size());
    }

    @Test
    void testEntityBlockFormIsNotRecorded() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperNaturalBlockChangeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var event = Mockito.mock(EntityBlockFormEvent.class);
        stubTransition(
            event,
            PaperBlockEventTestSupport.world(),
            1,
            Blocks.WATER.defaultBlockState(),
            Blocks.FROSTED_ICE.defaultBlockState()
        );

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testCancelledChangesAreNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperNaturalBlockChangeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();

        var fade = Mockito.mock(BlockFadeEvent.class);
        stubTransition(fade, world, 1, Blocks.ICE.defaultBlockState(), Blocks.WATER.defaultBlockState());
        Mockito.when(fade.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, fade);

        var leavesBlock = PaperBlockEventTestSupport.block(
            world, 2, 64, 0, Blocks.OAK_LEAVES.defaultBlockState(), Material.OAK_LEAVES
        );
        var leaves = Mockito.mock(LeavesDecayEvent.class);
        Mockito.when(leaves.getBlock()).thenReturn(leavesBlock);
        Mockito.when(leaves.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, leaves);

        var structure = Mockito.mock(StructureGrowEvent.class);
        Mockito.when(structure.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, structure);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static void stubTransition(
        BlockGrowEvent event,
        World world,
        int x,
        BlockState before,
        BlockState after
    ) {
        var block = PaperBlockEventTestSupport.block(world, x, 64, 0, before, Material.AIR);
        var newState = PaperBlockEventTestSupport.state(world, block, x, 64, 0, after);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getNewState()).thenReturn(newState);
    }

    private static void stubTransition(
        BlockFadeEvent event,
        World world,
        int x,
        BlockState before,
        BlockState after
    ) {
        var block = PaperBlockEventTestSupport.block(world, x, 64, 0, before, Material.AIR);
        var newState = PaperBlockEventTestSupport.state(world, block, x, 64, 0, after);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getNewState()).thenReturn(newState);
    }

    private static void stubTransition(
        MoistureChangeEvent event,
        World world,
        int x,
        BlockState before,
        BlockState after
    ) {
        var block = PaperBlockEventTestSupport.block(world, x, 64, 0, before, Material.AIR);
        var newState = PaperBlockEventTestSupport.state(world, block, x, 64, 0, after);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getNewState()).thenReturn(newState);
    }

    private static CompoundTag naturalPayload(
        BlockState before,
        BlockState after,
        String sourceEvent,
        String cause,
        BlockPosition source
    ) {
        var payload = new CompoundTag();
        payload.put("pre_state", PaperBlockStatePayloadCodec.blockState(before.asBlockData()));
        payload.put("post_state", PaperBlockStatePayloadCodec.blockState(after.asBlockData()));
        payload.putString("source_event", sourceEvent);
        if (cause != null) {
            payload.putString("cause", cause);
        }
        if (source != null) {
            payload.put("source", PaperBlockEventTestSupport.position(source));
        }
        return payload;
    }

    private static HashMap<Integer, EventSubmission> byX(Iterable<EventSubmission> submissions) {
        var result = new HashMap<Integer, EventSubmission>();
        for (var submission : submissions) {
            Assertions.assertNotNull(submission.position());
            Assertions.assertNull(result.put(submission.position().x(), submission));
        }
        return result;
    }
}
