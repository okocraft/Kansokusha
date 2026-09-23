package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.world.level.block.Blocks;
import org.bukkit.Material;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;

class PaperFireEventRelationTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testIgniteSpreadAndBurnHaveDistinctCanonicalOwnership() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var igniteListener = PaperBlockIgniteListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var naturalListener = PaperNaturalBlockChangeListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var burnListener = PaperBlockBurnListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var world = PaperBlockEventTestSupport.world();
        var sourceFire = PaperBlockEventTestSupport.block(
            world, 0, 64, 0, Blocks.FIRE.defaultBlockState(), Material.FIRE
        );
        var target = PaperBlockEventTestSupport.block(
            world, 1, 64, 0, Blocks.AIR.defaultBlockState(), Material.AIR
        );

        var originIgnite = Mockito.mock(BlockIgniteEvent.class);
        Mockito.when(originIgnite.getBlock()).thenReturn(target);
        Mockito.when(originIgnite.getCause()).thenReturn(BlockIgniteEvent.IgniteCause.LAVA);
        igniteListener.capture(originIgnite);
        igniteListener.finalizeEvent(originIgnite);

        var spreadIgnite = Mockito.mock(BlockIgniteEvent.class);
        Mockito.when(spreadIgnite.getCause()).thenReturn(BlockIgniteEvent.IgniteCause.SPREAD);
        Mockito.when(spreadIgnite.getBlock()).thenReturn(target);
        igniteListener.capture(spreadIgnite);
        igniteListener.finalizeEvent(spreadIgnite);

        var postFire = PaperBlockEventTestSupport.state(
            world, target, 1, 64, 0, Blocks.FIRE.defaultBlockState()
        );
        var spread = Mockito.mock(BlockSpreadEvent.class);
        Mockito.when(spread.getBlock()).thenReturn(target);
        Mockito.when(spread.getSource()).thenReturn(sourceFire);
        Mockito.when(spread.getNewState()).thenReturn(postFire);
        naturalListener.capture(spread);
        naturalListener.finalizeEvent(spread);

        var burned = PaperBlockEventTestSupport.block(
            world, 2, 64, 0, Blocks.OAK_PLANKS.defaultBlockState(), Material.OAK_PLANKS
        );
        var burn = Mockito.mock(BlockBurnEvent.class);
        Mockito.when(burn.getBlock()).thenReturn(burned);
        Mockito.when(burn.getIgnitingBlock()).thenReturn(sourceFire);
        burnListener.capture(burn);
        burnListener.finalizeEvent(burn);

        Assertions.assertEquals(3, api.submissions.size());
        var counts = new HashMap<net.kyori.adventure.key.Key, Integer>();
        for (var submission : api.submissions) {
            counts.merge(submission.eventType(), 1, Integer::sum);
        }
        Assertions.assertEquals(1, counts.get(PaperBlockIgniteListener.EVENT_TYPE));
        Assertions.assertEquals(1, counts.get(PaperNaturalBlockChangeListener.EVENT_TYPE));
        Assertions.assertEquals(1, counts.get(PaperBlockBurnListener.EVENT_TYPE));
        Mockito.verify(spreadIgnite, Mockito.never()).getBlock();
    }
}
