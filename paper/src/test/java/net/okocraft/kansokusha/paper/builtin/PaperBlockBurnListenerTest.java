package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.GameRules;
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
    void testBurnRecordsBurnedAndSourceStates() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockBurnListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var event = burnEvent(Blocks.OAK_PLANKS.defaultBlockState(), Material.OAK_PLANKS, true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperBlockBurnListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(20, 65, 30), submission.position());
        Assertions.assertEquals(new BlockActor(Key.key("minecraft", "fire")), submission.actor());
        Assertions.assertEquals(Key.key("minecraft", "oak_planks"), submission.targetType());

        var expected = new CompoundTag();
        expected.put(
            "pre_state",
            PaperBlockStatePayloadCodec.blockState(Blocks.OAK_PLANKS.defaultBlockState().asBlockData())
        );
        expected.put(
            "source",
            PaperBlockEventTestSupport.position(new BlockPosition(19, 65, 30))
        );
        expected.put(
            "source_state",
            PaperBlockStatePayloadCodec.blockState(Blocks.FIRE.defaultBlockState().asBlockData())
        );
        Assertions.assertEquals(expected, PaperPayloadNbtCodec.decode(submission.payload()));
    }

    @Test
    void testBurningTntIsLeftToTntPrimeWhenTntExplodes() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockBurnListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);

        PaperListenerTestSupport.fire(
            listener,
            burnEvent(Blocks.TNT.defaultBlockState(), Material.TNT, true)
        );

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testBurningTntIsRecordedWhenTntDoesNotExplode() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockBurnListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);

        PaperListenerTestSupport.fire(
            listener,
            burnEvent(Blocks.TNT.defaultBlockState(), Material.TNT, false)
        );

        Assertions.assertEquals(1, api.submissions.size());
    }

    @Test
    void testCancelledBurnIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockBurnListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var event = burnEvent(Blocks.OAK_PLANKS.defaultBlockState(), Material.OAK_PLANKS, true);
        Mockito.when(event.isCancelled()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static BlockBurnEvent burnEvent(
        net.minecraft.world.level.block.state.BlockState state,
        Material material,
        boolean tntExplodes
    ) {
        var world = PaperBlockEventTestSupport.world();
        Mockito.when(world.getGameRuleValue(GameRules.TNT_EXPLODES)).thenReturn(tntExplodes);
        var burned = PaperBlockEventTestSupport.block(world, 20, 65, 30, state, material);
        var source = PaperBlockEventTestSupport.block(
            world, 19, 65, 30, Blocks.FIRE.defaultBlockState(), Material.FIRE
        );
        var event = Mockito.mock(BlockBurnEvent.class);
        Mockito.when(event.getBlock()).thenReturn(burned);
        Mockito.when(event.getIgnitingBlock()).thenReturn(source);
        return event;
    }
}
