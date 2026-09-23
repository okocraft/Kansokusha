package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.world.level.block.Blocks;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;

class PaperFertilizationEventRelationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testPlayerBonemealStructureGrowIsOwnedByFertilization() {
        var fixture = fixture();
        var structure = structureGrow(fixture, true);

        fixture.listener().capture(structure);
        fixture.listener().finalizeEvent(structure);
        fixture.listener().discardFertilizedStructureGrow(fertilize(fixture, false));

        Assertions.assertTrue(fixture.api().submissions.isEmpty());
        Assertions.assertTrue(fixture.deferred().isEmpty());
        Assertions.assertEquals(0, fixture.listener().inFlightCount());
    }

    @Test
    void testDispenserBonemealStructureGrowIsOwnedByAcceptedFertilization() {
        assertDispenserBonemealIsOwnedByFertilization(false);
    }

    @Test
    void testDispenserBonemealStructureGrowIsOwnedByCancelledFertilization() {
        assertDispenserBonemealIsOwnedByFertilization(true);
    }

    private static void assertDispenserBonemealIsOwnedByFertilization(boolean cancelled) {
        var fixture = fixture();
        var structure = structureGrow(fixture, false);

        fixture.listener().capture(structure);
        fixture.listener().finalizeEvent(structure);

        Assertions.assertTrue(fixture.api().submissions.isEmpty());
        Assertions.assertEquals(1, fixture.deferred().size());
        Assertions.assertEquals(1, fixture.listener().inFlightCount());

        fixture.listener().discardFertilizedStructureGrow(fertilize(fixture, cancelled));
        fixture.deferred().remove().run();

        Assertions.assertTrue(fixture.api().submissions.isEmpty());
        Assertions.assertEquals(0, fixture.listener().inFlightCount());
    }

    private static Fixture fixture() {
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
        return new Fixture(api, listener, deferred, List.of(state));
    }

    private static StructureGrowEvent structureGrow(Fixture fixture, boolean fromBonemeal) {
        var event = Mockito.mock(StructureGrowEvent.class);
        Mockito.when(event.isFromBonemeal()).thenReturn(fromBonemeal);
        if (!fromBonemeal) {
            Mockito.when(event.getSpecies()).thenReturn(TreeType.TREE);
            Mockito.when(event.getBlocks()).thenReturn(fixture.changedStates());
        }
        return event;
    }

    private static BlockFertilizeEvent fertilize(Fixture fixture, boolean cancelled) {
        var event = Mockito.mock(BlockFertilizeEvent.class);
        Mockito.when(event.getBlocks()).thenReturn(fixture.changedStates());
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        return event;
    }

    private record Fixture(
        PaperBlockEventTestSupport.RecordingApi api,
        PaperNaturalBlockChangeListener listener,
        ArrayDeque<Runnable> deferred,
        List<org.bukkit.block.BlockState> changedStates
    ) {
    }
}
