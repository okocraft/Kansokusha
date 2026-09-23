package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

class PaperNaturalBlockChangeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testBlockSpreadCapturesCanonicalFirePropagationSnapshot() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperNaturalBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var changed = PaperBlockEventTestSupport.block(
            world, 11, 64, 10, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var source = PaperBlockEventTestSupport.block(
            world, 10, 64, 10, Blocks.FIRE.defaultBlockState(), Material.FIRE
        );
        var post = PaperBlockEventTestSupport.state(
            world, changed, 11, 64, 10, Blocks.FIRE.defaultBlockState()
        );
        var event = Mockito.mock(BlockSpreadEvent.class);
        Mockito.when(event.getBlock()).thenReturn(changed);
        Mockito.when(event.getSource()).thenReturn(source);
        Mockito.when(event.getNewState()).thenReturn(post);

        listener.capture(event);
        Mockito.when(changed.getBlockData()).thenReturn(Blocks.LAVA.defaultBlockState().asBlockData());
        Mockito.when(post.getBlockData()).thenReturn(Blocks.WATER.defaultBlockState().asBlockData());
        listener.finalizeEvent(event);

        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperNaturalBlockChangeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(11, 64, 10), submission.position());
        Assertions.assertNull(submission.subject());

        var expected = naturalPayload(
            Blocks.AIR.defaultBlockState(),
            Blocks.FIRE.defaultBlockState(),
            "block_spread",
            "fire",
            new BlockPosition(10, 64, 10)
        );
        Assertions.assertEquals(expected, PaperBlockStatePayloadCodec.decode(submission.payload()));
        Mockito.verify(changed, Mockito.times(1)).getBlockData();
    }

    @Test
    void testInheritedGrowHandlersDoNotReplaceSpreadSemantics() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var changed = PaperBlockEventTestSupport.block(
            world, 11, 64, 10, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var source = PaperBlockEventTestSupport.block(
            world, 10, 64, 10, Blocks.FIRE.defaultBlockState(), Material.FIRE
        );
        var post = PaperBlockEventTestSupport.state(
            world, changed, 11, 64, 10, Blocks.FIRE.defaultBlockState()
        );
        var event = Mockito.mock(BlockSpreadEvent.class);
        Mockito.when(event.getBlock()).thenReturn(changed);
        Mockito.when(event.getSource()).thenReturn(source);
        Mockito.when(event.getNewState()).thenReturn(post);

        listener.capture((BlockGrowEvent) event);
        listener.capture((BlockFormEvent) event);
        listener.capture(event);
        listener.finalizeEvent((BlockGrowEvent) event);
        listener.finalizeEvent((BlockFormEvent) event);
        listener.finalizeEvent(event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(
            naturalPayload(
                Blocks.AIR.defaultBlockState(),
                Blocks.FIRE.defaultBlockState(),
                "block_spread",
                "fire",
                new BlockPosition(10, 64, 10)
            ),
            PaperBlockStatePayloadCodec.decode(submission.payload())
        );
    }

    @Test
    void testSingleBlockSourcesUseCommonSchema() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();

        var fadeBlock = PaperBlockEventTestSupport.block(
            world, 1, 70, 1, Blocks.ICE.defaultBlockState(), Material.ICE
        );
        var fadeState = PaperBlockEventTestSupport.state(
            world, fadeBlock, 1, 70, 1, Blocks.WATER.defaultBlockState()
        );
        var fade = Mockito.mock(BlockFadeEvent.class);
        Mockito.when(fade.getBlock()).thenReturn(fadeBlock);
        Mockito.when(fade.getNewState()).thenReturn(fadeState);
        listener.capture(fade);
        listener.finalizeEvent(fade);

        var formBlock = PaperBlockEventTestSupport.block(
            world, 2, 70, 2, Blocks.WATER.defaultBlockState(), Material.WATER
        );
        var formState = PaperBlockEventTestSupport.state(
            world, formBlock, 2, 70, 2, Blocks.ICE.defaultBlockState()
        );
        var form = Mockito.mock(BlockFormEvent.class);
        Mockito.when(form.getBlock()).thenReturn(formBlock);
        Mockito.when(form.getNewState()).thenReturn(formState);
        listener.capture(form);
        listener.finalizeEvent(form);

        var growBlock = PaperBlockEventTestSupport.block(
            world, 3, 70, 3, Blocks.WHEAT.defaultBlockState(), Material.WHEAT
        );
        var growState = PaperBlockEventTestSupport.state(
            world, growBlock, 3, 70, 3, Blocks.WHEAT.defaultBlockState()
        );
        var grow = Mockito.mock(BlockGrowEvent.class);
        Mockito.when(grow.getBlock()).thenReturn(growBlock);
        Mockito.when(grow.getNewState()).thenReturn(growState);
        listener.capture(grow);
        listener.finalizeEvent(grow);

        var moistureBlock = PaperBlockEventTestSupport.block(
            world, 4, 70, 4, Blocks.FARMLAND.defaultBlockState(), Material.FARMLAND
        );
        var moistureState = PaperBlockEventTestSupport.state(
            world, moistureBlock, 4, 70, 4, Blocks.FARMLAND.defaultBlockState()
        );
        var moisture = Mockito.mock(MoistureChangeEvent.class);
        Mockito.when(moisture.getBlock()).thenReturn(moistureBlock);
        Mockito.when(moisture.getNewState()).thenReturn(moistureState);
        listener.capture(moisture);
        listener.finalizeEvent(moisture);

        var leavesBlock = PaperBlockEventTestSupport.block(
            world, 5, 70, 5, Blocks.OAK_LEAVES.defaultBlockState(), Material.OAK_LEAVES
        );
        var leaves = Mockito.mock(LeavesDecayEvent.class);
        Mockito.when(leaves.getBlock()).thenReturn(leavesBlock);
        listener.capture(leaves);
        listener.finalizeEvent(leaves);

        var byX = byX(api.submissions);
        Assertions.assertEquals(
            naturalPayload(
                Blocks.ICE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                "block_fade",
                null,
                null
            ),
            PaperBlockStatePayloadCodec.decode(byX.get(1).payload())
        );
        Assertions.assertEquals(
            naturalPayload(
                Blocks.WATER.defaultBlockState(),
                Blocks.ICE.defaultBlockState(),
                "block_form",
                null,
                null
            ),
            PaperBlockStatePayloadCodec.decode(byX.get(2).payload())
        );
        Assertions.assertEquals(
            naturalPayload(
                Blocks.WHEAT.defaultBlockState(),
                Blocks.WHEAT.defaultBlockState(),
                "block_grow",
                null,
                null
            ),
            PaperBlockStatePayloadCodec.decode(byX.get(3).payload())
        );
        Assertions.assertEquals(
            naturalPayload(
                Blocks.FARMLAND.defaultBlockState(),
                Blocks.FARMLAND.defaultBlockState(),
                "moisture_change",
                null,
                null
            ),
            PaperBlockStatePayloadCodec.decode(byX.get(4).payload())
        );
        Assertions.assertEquals(
            naturalPayload(
                Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                "leaves_decay",
                null,
                null
            ),
            PaperBlockStatePayloadCodec.decode(byX.get(5).payload())
        );
    }

    @Test
    void testStructureGrowSubmitsEachChangedBlockWithOneTimestamp() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var clock = Mockito.mock(Clock.class);
        Mockito.when(clock.instant()).thenReturn(
            OCCURRED_AT,
            OCCURRED_AT.plusSeconds(1),
            OCCURRED_AT.plusSeconds(2)
        );
        var deferred = new ArrayDeque<Runnable>();
        var listener = PaperNaturalBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            clock,
            (location, task) -> deferred.add(task)
        );
        var world = PaperBlockEventTestSupport.world();
        var blocks = new ArrayList<org.bukkit.block.Block>();
        var states = new ArrayList<org.bukkit.block.BlockState>();
        for (int i = 0; i < 3; i++) {
            var block = PaperBlockEventTestSupport.block(
                world,
                100 + i,
                70,
                -i,
                Blocks.AIR.defaultBlockState(),
                Material.AIR
            );
            blocks.add(block);
            states.add(PaperBlockEventTestSupport.state(
                world,
                block,
                100 + i,
                70,
                -i,
                Blocks.OAK_LOG.defaultBlockState()
            ));
        }
        var event = Mockito.mock(StructureGrowEvent.class);
        Mockito.when(event.getBlocks()).thenReturn(states);
        Mockito.when(event.getSpecies()).thenReturn(TreeType.BIRCH);
        Mockito.when(event.isFromBonemeal()).thenReturn(false);

        listener.capture(event);
        for (int i = 0; i < states.size(); i++) {
            Mockito.when(states.get(i).getBlockData())
                .thenReturn(Blocks.LAVA.defaultBlockState().asBlockData());
            Mockito.when(blocks.get(i).getBlockData())
                .thenReturn(Blocks.STONE.defaultBlockState().asBlockData());
        }
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, listener.inFlightCount());
        Assertions.assertEquals(1, deferred.size());
        deferred.remove().run();

        Assertions.assertEquals(3, api.submissions.size());
        var byX = byX(api.submissions);
        for (int i = 0; i < 3; i++) {
            var submission = byX.get(100 + i);
            Assertions.assertNotNull(submission);
            Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
            Assertions.assertEquals(
                naturalPayload(
                    Blocks.AIR.defaultBlockState(),
                    Blocks.OAK_LOG.defaultBlockState(),
                    "structure_grow",
                    "birch",
                    null
                ),
                PaperBlockStatePayloadCodec.decode(submission.payload())
            );
        }
        Mockito.verify(clock, Mockito.times(1)).instant();
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testBonemealStructureGrowIsReservedForBlockFertilize() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var event = Mockito.mock(StructureGrowEvent.class);
        Mockito.when(event.isFromBonemeal()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
        Mockito.verify(event, Mockito.never()).getBlocks();
    }

    @Test
    void testDispenserBonemealAcceptedFertilizeOwnsStructureChanges() {
        assertDispenserBonemealIsReservedForBlockFertilize(false);
    }

    @Test
    void testDispenserBonemealCancelledFertilizeDropsStructureChanges() {
        assertDispenserBonemealIsReservedForBlockFertilize(true);
    }

    @Test
    void testEntityBlockFormIsExcludedFromNaturalChanges() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var block = PaperBlockEventTestSupport.block(
            world, 30, 64, 30, Blocks.WATER.defaultBlockState(), Material.WATER
        );
        var state = PaperBlockEventTestSupport.state(
            world, block, 30, 64, 30, Blocks.FROSTED_ICE.defaultBlockState()
        );
        var entity = Mockito.mock(Entity.class);
        var event = new EntityBlockFormEvent(entity, block, state);

        Assertions.assertSame(BlockFormEvent.getHandlerList(), event.getHandlers());

        listener.capture((BlockFormEvent) event);
        listener.finalizeEvent((BlockFormEvent) event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testLeavesDecayDropsSnapshotWhenBlockWasReplacedDuringEvent() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var leaves = PaperBlockEventTestSupport.block(
            world, 31, 70, 31, Blocks.OAK_LEAVES.defaultBlockState(), Material.OAK_LEAVES
        );
        var event = Mockito.mock(LeavesDecayEvent.class);
        Mockito.when(event.getBlock()).thenReturn(leaves);

        listener.capture(event);
        Mockito.when(leaves.getType()).thenReturn(Material.STONE);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testFinalCancellationDropsCapturedChanges() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();

        var changed = PaperBlockEventTestSupport.block(
            world, 1, 64, 1, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var source = PaperBlockEventTestSupport.block(
            world, 0, 64, 1, Blocks.FIRE.defaultBlockState(), Material.FIRE
        );
        var spreadState = PaperBlockEventTestSupport.state(
            world, changed, 1, 64, 1, Blocks.FIRE.defaultBlockState()
        );
        var spread = Mockito.mock(BlockSpreadEvent.class);
        Mockito.when(spread.getBlock()).thenReturn(changed);
        Mockito.when(spread.getSource()).thenReturn(source);
        Mockito.when(spread.getNewState()).thenReturn(spreadState);
        Mockito.when(spread.isCancelled()).thenReturn(true);
        listener.capture(spread);
        listener.finalizeEvent(spread);

        var structureBlock = PaperBlockEventTestSupport.block(
            world, 2, 64, 2, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var structureState = PaperBlockEventTestSupport.state(
            world, structureBlock, 2, 64, 2, Blocks.OAK_LOG.defaultBlockState()
        );
        var structure = Mockito.mock(StructureGrowEvent.class);
        Mockito.when(structure.isFromBonemeal()).thenReturn(false);
        Mockito.when(structure.getSpecies()).thenReturn(TreeType.BIRCH);
        Mockito.when(structure.getBlocks()).thenReturn(List.of(structureState));
        Mockito.when(structure.isCancelled()).thenReturn(true);
        listener.capture(structure);
        listener.finalizeEvent(structure);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static void assertDispenserBonemealIsReservedForBlockFertilize(boolean cancelled) {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var deferred = new ArrayDeque<Runnable>();
        var listener = PaperNaturalBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC),
            (location, task) -> deferred.add(task)
        );
        var world = PaperBlockEventTestSupport.world();
        var block = PaperBlockEventTestSupport.block(
            world, 8, 64, 8, Blocks.OAK_SAPLING.defaultBlockState(), Material.OAK_SAPLING
        );
        var state = PaperBlockEventTestSupport.state(
            world, block, 8, 64, 8, Blocks.OAK_LOG.defaultBlockState()
        );
        var changedStates = List.of(state);

        var structure = Mockito.mock(StructureGrowEvent.class);
        Mockito.when(structure.isFromBonemeal()).thenReturn(false);
        Mockito.when(structure.getSpecies()).thenReturn(TreeType.TREE);
        Mockito.when(structure.getBlocks()).thenReturn(changedStates);

        listener.capture(structure);
        listener.finalizeEvent(structure);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, listener.inFlightCount());
        Assertions.assertEquals(1, deferred.size());

        var fertilize = Mockito.mock(BlockFertilizeEvent.class);
        Mockito.when(fertilize.getBlocks()).thenReturn(changedStates);
        Mockito.when(fertilize.isCancelled()).thenReturn(cancelled);
        listener.discardFertilizedChanges(fertilize);

        deferred.remove().run();

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static PaperNaturalBlockChangeListener listener(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        return PaperNaturalBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.systemUTC(),
            (location, task) -> task.run()
        );
    }

    private static CompoundTag naturalPayload(
        net.minecraft.world.level.block.state.BlockState before,
        net.minecraft.world.level.block.state.BlockState after,
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
