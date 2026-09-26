package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockIgniteEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class PaperBlockIgniteListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T00:00:00Z");
    private static final UUID PLAYER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testPlayerIgniteRecordsCauseActorAndPreState() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockIgniteListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var target = PaperBlockEventTestSupport.block(
            world, 10, 64, 20, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var source = PaperBlockEventTestSupport.block(
            world, 9, 64, 20, Blocks.NETHERRACK.defaultBlockState(), Material.NETHERRACK
        );
        var player = player();

        var event = Mockito.mock(BlockIgniteEvent.class);
        Mockito.when(event.getBlock()).thenReturn(target);
        Mockito.when(event.getCause()).thenReturn(BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL);
        Mockito.when(event.getIgnitingBlock()).thenReturn(source);
        Mockito.when(event.getIgnitingEntity()).thenReturn(player);
        Mockito.when(event.getPlayer()).thenReturn(player);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperBlockIgniteListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(10, 64, 20), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());

        var expected = new CompoundTag();
        expected.put(
            "pre_state",
            PaperBlockStatePayloadCodec.blockState(Blocks.AIR.defaultBlockState().asBlockData())
        );
        expected.putString("cause", "FLINT_AND_STEEL");
        expected.put(
            "source",
            PaperBlockEventTestSupport.position(new BlockPosition(9, 64, 20))
        );
        expected.put(
            "source_state",
            PaperBlockStatePayloadCodec.blockState(Blocks.NETHERRACK.defaultBlockState().asBlockData())
        );
        expected.putString("actor_entity_uuid", PLAYER_ID.toString());
        expected.putString("actor_entity_type", "PLAYER");
        Assertions.assertEquals(expected, PaperPayloadNbtCodec.decode(submission.payload()));
    }

    @Test
    void testIgniteWithoutPlayerHasNoSubject() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockIgniteListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var event = igniteEvent(BlockIgniteEvent.IgniteCause.LAVA);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(1, api.submissions.size());
        Assertions.assertNull(api.submissions.remove().subject());
    }

    @Test
    void testCancelledIgniteIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockIgniteListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var event = igniteEvent(BlockIgniteEvent.IgniteCause.LAVA);
        Mockito.when(event.isCancelled()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testSpreadIgniteIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperBlockIgniteListener.register(api, PaperBlockEventTestSupport.SERVER_KEY);
        var event = igniteEvent(BlockIgniteEvent.IgniteCause.SPREAD);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static BlockIgniteEvent igniteEvent(BlockIgniteEvent.IgniteCause cause) {
        var block = PaperBlockEventTestSupport.block(
            PaperBlockEventTestSupport.world(), 1, 2, 3, Blocks.AIR.defaultBlockState(), Material.AIR
        );
        var event = Mockito.mock(BlockIgniteEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getCause()).thenReturn(cause);
        return event;
    }

    private static Player player() {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);
        return player;
    }
}
