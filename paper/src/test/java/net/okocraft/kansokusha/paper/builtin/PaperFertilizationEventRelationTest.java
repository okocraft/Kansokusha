package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.world.level.block.Blocks;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

class PaperFertilizationEventRelationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testPlayerBonemealStructureGrowIsOwnedByFertilization() {
        var fixture = structureFixture();
        var structure = structureGrow(fixture, true);

        fixture.listener().capture(structure);
        fixture.listener().finalizeEvent(structure);
        dispatchFertilize(
            fixture.listener(),
            fertilize(fixture.changedStates(), false)
        );

        Assertions.assertTrue(fixture.api().submissions.isEmpty());
        Assertions.assertTrue(fixture.deferred().isEmpty());
        Assertions.assertEquals(0, fixture.listener().inFlightCount());
    }

    @Test
    void testDispenserBonemealStructureGrowIsOwnedByAcceptedFertilization() {
        assertDispenserBonemealStructureGrowIsOwnedByFertilization(false);
    }

    @Test
    void testDispenserBonemealStructureGrowIsOwnedByCancelledFertilization() {
        assertDispenserBonemealStructureGrowIsOwnedByFertilization(true);
    }

    @Test
    void testCocoaBonemealGrowIsOwnedByAcceptedFertilization() {
        assertCocoaBonemealGrowIsOwnedByFertilization(false);
    }

    @Test
    void testCocoaBonemealGrowIsOwnedByCancelledFertilization() {
        assertCocoaBonemealGrowIsOwnedByFertilization(true);
    }

    @Test
    void testCocoaBonemealGrowRemainsFertilizationOwnedAfterAcceptedListRemoval() {
        assertCocoaBonemealGrowRemainsOwnedAfterListRemoval(false);
    }

    @Test
    void testCocoaBonemealAcceptedListRemovalProducesNoStateChangeSubmission() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var deferred = new ArrayDeque<Runnable>();
        var clock = Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
        var natural = PaperNaturalBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            clock,
            (location, task) -> deferred.add(task)
        );
        var fertilizeListener = PaperBlockFertilizeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            clock
        );
        var world = PaperBlockEventTestSupport.world();
        var cocoa = PaperBlockEventTestSupport.block(
            world, 18, 65, 18, Blocks.COCOA.defaultBlockState(), Material.COCOA
        );
        var grownState = PaperBlockEventTestSupport.state(
            world,
            cocoa,
            18,
            65,
            18,
            Blocks.COCOA.defaultBlockState().setValue(
                net.minecraft.world.level.block.CocoaBlock.AGE,
                1
            )
        );
        var grow = Mockito.mock(BlockGrowEvent.class);
        Mockito.when(grow.getBlock()).thenReturn(cocoa);
        Mockito.when(grow.getNewState()).thenReturn(grownState);

        natural.capture(grow);
        natural.finalizeEvent(grow);

        var changedStates = new ArrayList<org.bukkit.block.BlockState>();
        changedStates.add(grownState);
        var fertilize = Mockito.mock(BlockFertilizeEvent.class);
        Mockito.when(fertilize.getBlock()).thenReturn(cocoa);
        Mockito.when(fertilize.getBlocks()).thenReturn(changedStates);

        natural.capture(fertilize);
        changedStates.remove(grownState);
        natural.discardFertilizedChanges(fertilize);
        PaperListenerTestSupport.fire(fertilizeListener, fertilize);
        deferred.remove().run();

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, natural.inFlightCount());
    }

    @Test
    void testCocoaBonemealGrowRemainsFertilizationOwnedAfterCancelledListRemoval() {
        assertCocoaBonemealGrowRemainsOwnedAfterListRemoval(true);
    }

    @Test
    void testRootedDirtBonemealSpreadIsOwnedByAcceptedFertilization() {
        assertRootedDirtBonemealSpreadIsOwnedByFertilization(false);
    }

    @Test
    void testRootedDirtBonemealSpreadIsOwnedByCancelledFertilization() {
        assertRootedDirtBonemealSpreadIsOwnedByFertilization(true);
    }

    @Test
    void testStemIntermediateGrowthIsOwnedByAcceptedFertilization() {
        assertStemIntermediateGrowthIsOwnedByFertilization(false);
    }

    @Test
    void testStemIntermediateGrowthIsOwnedByCancelledFertilization() {
        assertStemIntermediateGrowthIsOwnedByFertilization(true);
    }

    private static void assertDispenserBonemealStructureGrowIsOwnedByFertilization(
        boolean cancelled
    ) {
        var fixture = structureFixture();
        var structure = structureGrow(fixture, false);

        fixture.listener().capture(structure);
        fixture.listener().finalizeEvent(structure);

        Assertions.assertTrue(fixture.api().submissions.isEmpty());
        Assertions.assertEquals(1, fixture.deferred().size());
        Assertions.assertEquals(1, fixture.listener().inFlightCount());

        dispatchFertilize(
            fixture.listener(),
            fertilize(fixture.changedStates(), cancelled)
        );
        fixture.deferred().remove().run();

        Assertions.assertTrue(fixture.api().submissions.isEmpty());
        Assertions.assertEquals(0, fixture.listener().inFlightCount());
    }

    private static void assertCocoaBonemealGrowIsOwnedByFertilization(boolean cancelled) {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var deferred = new ArrayDeque<Runnable>();
        var listener = listener(api, deferred);
        var world = PaperBlockEventTestSupport.world();
        var cocoa = PaperBlockEventTestSupport.block(
            world, 12, 65, 12, Blocks.COCOA.defaultBlockState(), Material.COCOA
        );
        var grownState = PaperBlockEventTestSupport.state(
            world,
            cocoa,
            12,
            65,
            12,
            Blocks.COCOA.defaultBlockState().setValue(
                net.minecraft.world.level.block.CocoaBlock.AGE,
                1
            )
        );
        var grow = Mockito.mock(BlockGrowEvent.class);
        Mockito.when(grow.getBlock()).thenReturn(cocoa);
        Mockito.when(grow.getNewState()).thenReturn(grownState);

        listener.capture(grow);
        listener.finalizeEvent(grow);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, deferred.size());
        Assertions.assertEquals(1, listener.inFlightCount());

        dispatchFertilize(
            listener,
            fertilize(List.of(grownState), cancelled)
        );
        deferred.remove().run();

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static void assertCocoaBonemealGrowRemainsOwnedAfterListRemoval(
        boolean cancelled
    ) {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var deferred = new ArrayDeque<Runnable>();
        var listener = listener(api, deferred);
        var world = PaperBlockEventTestSupport.world();
        var cocoa = PaperBlockEventTestSupport.block(
            world, 16, 65, 16, Blocks.COCOA.defaultBlockState(), Material.COCOA
        );
        var grownState = PaperBlockEventTestSupport.state(
            world,
            cocoa,
            16,
            65,
            16,
            Blocks.COCOA.defaultBlockState().setValue(
                net.minecraft.world.level.block.CocoaBlock.AGE,
                1
            )
        );
        var grow = Mockito.mock(BlockGrowEvent.class);
        Mockito.when(grow.getBlock()).thenReturn(cocoa);
        Mockito.when(grow.getNewState()).thenReturn(grownState);

        listener.capture(grow);
        listener.finalizeEvent(grow);

        var changedStates = new ArrayList<org.bukkit.block.BlockState>();
        changedStates.add(grownState);
        var fertilize = fertilize(changedStates, cancelled);

        listener.capture(fertilize);
        changedStates.remove(grownState);
        listener.discardFertilizedChanges(fertilize);
        deferred.remove().run();

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static void assertRootedDirtBonemealSpreadIsOwnedByFertilization(
        boolean cancelled
    ) {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var deferred = new ArrayDeque<Runnable>();
        var listener = listener(api, deferred);
        var world = PaperBlockEventTestSupport.world();
        var source = PaperBlockEventTestSupport.block(
            world, 20, 65, 20, Blocks.ROOTED_DIRT.defaultBlockState(), Material.ROOTED_DIRT
        );
        var target = PaperBlockEventTestSupport.block(
            world, 20, 64, 20, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var spreadState = PaperBlockEventTestSupport.state(
            world, target, 20, 64, 20, Blocks.HANGING_ROOTS.defaultBlockState()
        );
        var spread = Mockito.mock(BlockSpreadEvent.class);
        Mockito.when(spread.getSource()).thenReturn(source);
        Mockito.when(spread.getBlock()).thenReturn(target);
        Mockito.when(spread.getNewState()).thenReturn(spreadState);

        listener.capture(spread);
        listener.finalizeEvent(spread);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(1, deferred.size());
        Assertions.assertEquals(1, listener.inFlightCount());

        dispatchFertilize(
            listener,
            fertilize(List.of(spreadState), cancelled)
        );
        deferred.remove().run();

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static void assertStemIntermediateGrowthIsOwnedByFertilization(
        boolean cancelled
    ) {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var deferred = new ArrayDeque<Runnable>();
        var listener = listener(api, deferred);
        var world = PaperBlockEventTestSupport.world();

        var stem = PaperBlockEventTestSupport.block(
            world, 30, 65, 30, Blocks.MELON_STEM.defaultBlockState(), Material.MELON_STEM
        );
        var ageSeven = PaperBlockEventTestSupport.state(
            world,
            stem,
            30,
            65,
            30,
            Blocks.MELON_STEM.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7,
                7
            )
        );
        var stemGrow = Mockito.mock(BlockGrowEvent.class);
        Mockito.when(stemGrow.getBlock()).thenReturn(stem);
        Mockito.when(stemGrow.getNewState()).thenReturn(ageSeven);

        listener.capture(stemGrow);
        listener.finalizeEvent(stemGrow);

        var fruit = PaperBlockEventTestSupport.block(
            world, 31, 65, 30, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var fruitState = PaperBlockEventTestSupport.state(
            world, fruit, 31, 65, 30, Blocks.MELON.defaultBlockState()
        );
        var fruitGrow = Mockito.mock(BlockGrowEvent.class);
        Mockito.when(fruitGrow.getBlock()).thenReturn(fruit);
        Mockito.when(fruitGrow.getNewState()).thenReturn(fruitState);

        listener.capture(fruitGrow);
        listener.finalizeEvent(fruitGrow);

        var attachedStem = PaperBlockEventTestSupport.state(
            world, stem, 30, 65, 30, Blocks.ATTACHED_MELON_STEM.defaultBlockState()
        );

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(2, deferred.size());
        Assertions.assertEquals(2, listener.inFlightCount());

        dispatchFertilize(
            listener,
            fertilize(List.of(attachedStem, fruitState), cancelled)
        );
        while (!deferred.isEmpty()) {
            deferred.remove().run();
        }

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testEarlierNaturalGrowthAtSamePositionIsNotClaimedByLaterFertilization() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var deferred = new ArrayDeque<Runnable>();
        var listener = listener(api, deferred);
        var world = PaperBlockEventTestSupport.world();
        var cocoa = PaperBlockEventTestSupport.block(
            world, 40, 65, 40, Blocks.COCOA.defaultBlockState(), Material.COCOA
        );

        var naturalState = PaperBlockEventTestSupport.state(
            world,
            cocoa,
            40,
            65,
            40,
            Blocks.COCOA.defaultBlockState().setValue(
                net.minecraft.world.level.block.CocoaBlock.AGE,
                1
            )
        );
        var naturalGrow = Mockito.mock(BlockGrowEvent.class);
        Mockito.when(naturalGrow.getBlock()).thenReturn(cocoa);
        Mockito.when(naturalGrow.getNewState()).thenReturn(naturalState);
        listener.capture(naturalGrow);
        listener.finalizeEvent(naturalGrow);

        Mockito.when(cocoa.getBlockData()).thenReturn(
            Blocks.COCOA.defaultBlockState().setValue(
                net.minecraft.world.level.block.CocoaBlock.AGE,
                1
            ).asBlockData()
        );

        var fertilizedState = PaperBlockEventTestSupport.state(
            world,
            cocoa,
            40,
            65,
            40,
            Blocks.COCOA.defaultBlockState().setValue(
                net.minecraft.world.level.block.CocoaBlock.AGE,
                2
            )
        );
        var fertilizedGrow = Mockito.mock(BlockGrowEvent.class);
        Mockito.when(fertilizedGrow.getBlock()).thenReturn(cocoa);
        Mockito.when(fertilizedGrow.getNewState()).thenReturn(fertilizedState);
        listener.capture(fertilizedGrow);
        listener.finalizeEvent(fertilizedGrow);

        dispatchFertilize(
            listener,
            fertilize(List.of(fertilizedState), false)
        );

        while (!deferred.isEmpty()) {
            deferred.remove().run();
        }

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static StructureFixture structureFixture() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var deferred = new ArrayDeque<Runnable>();
        var listener = listener(api, deferred);
        var world = PaperBlockEventTestSupport.world();
        var block = PaperBlockEventTestSupport.block(
            world, 8, 64, 8, Blocks.OAK_SAPLING.defaultBlockState(), Material.OAK_SAPLING
        );
        var state = PaperBlockEventTestSupport.state(
            world, block, 8, 64, 8, Blocks.OAK_LOG.defaultBlockState()
        );
        return new StructureFixture(api, listener, deferred, List.of(state));
    }

    private static PaperNaturalBlockChangeListener listener(
        PaperBlockEventTestSupport.RecordingApi api,
        ArrayDeque<Runnable> deferred
    ) {
        return PaperNaturalBlockChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC),
            (location, task) -> deferred.add(task)
        );
    }

    private static StructureGrowEvent structureGrow(
        StructureFixture fixture,
        boolean fromBonemeal
    ) {
        var event = Mockito.mock(StructureGrowEvent.class);
        Mockito.when(event.isFromBonemeal()).thenReturn(fromBonemeal);
        if (!fromBonemeal) {
            Mockito.when(event.getSpecies()).thenReturn(TreeType.TREE);
            Mockito.when(event.getBlocks()).thenReturn(fixture.changedStates());
        }
        return event;
    }

    private static void dispatchFertilize(
        PaperNaturalBlockChangeListener listener,
        BlockFertilizeEvent event
    ) {
        listener.capture(event);
        listener.discardFertilizedChanges(event);
    }

    private static BlockFertilizeEvent fertilize(
        List<org.bukkit.block.BlockState> changedStates,
        boolean cancelled
    ) {
        var event = Mockito.mock(BlockFertilizeEvent.class);
        Mockito.when(event.getBlocks()).thenReturn(changedStates);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        return event;
    }

    private record StructureFixture(
        PaperBlockEventTestSupport.RecordingApi api,
        PaperNaturalBlockChangeListener listener,
        ArrayDeque<Runnable> deferred,
        List<org.bukkit.block.BlockState> changedStates
    ) {
    }
}
