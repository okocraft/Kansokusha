package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class PaperCauldronLevelChangeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174002");
    private static final UUID ENTITY_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174003");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testPlayerChangeKeepsReasonActorAndLowestStates() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperCauldronLevelChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var world = PaperBlockEventTestSupport.world();
        var oldState = waterCauldron(2);
        var newState = waterCauldron(1);
        var block = PaperBlockEventTestSupport.block(
            world, 10, 64, 10, oldState, Material.WATER_CAULDRON
        );
        var changed = PaperBlockEventTestSupport.state(
            world, block, 10, 64, 10, newState
        );
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);

        var event = Mockito.mock(CauldronLevelChangeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getEntity()).thenReturn(player);
        Mockito.when(event.getReason()).thenReturn(
            CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL
        );
        Mockito.when(event.getNewState()).thenReturn(changed);

        listener.capture(event);

        Mockito.when(block.getBlockData()).thenReturn(
            Blocks.LAVA_CAULDRON.defaultBlockState().asBlockData()
        );
        Mockito.when(changed.getBlockData()).thenReturn(Blocks.AIR.defaultBlockState().asBlockData());
        Mockito.when(event.getEntity()).thenReturn(null);
        Mockito.when(event.getReason()).thenReturn(
            CauldronLevelChangeEvent.ChangeReason.NATURAL_FILL
        );

        listener.finalizeEvent(event);

        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperCauldronLevelChangeListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(10, 64, 10), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        Assertions.assertEquals(
            cauldronPayload(
                oldState,
                newState,
                CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL,
                "player",
                PLAYER_ID,
                EntityType.PLAYER.name()
            ),
            PaperBlockStatePayloadCodec.decode(submission.payload())
        );
        Assertions.assertEquals(0, listener.inFlightCount());
        Mockito.verify(block, Mockito.times(1)).getBlockData();
        Mockito.verify(changed, Mockito.times(1)).getBlockData();
    }

    @Test
    void testNonPlayerActorIsPayloadMetadataWithoutSubject() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperCauldronLevelChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var oldState = waterCauldron(3);
        var newState = waterCauldron(2);
        var block = PaperBlockEventTestSupport.block(
            world, 1, 2, 3, oldState, Material.WATER_CAULDRON
        );
        var changed = PaperBlockEventTestSupport.state(
            world, block, 1, 2, 3, newState
        );
        var entity = Mockito.mock(Entity.class);
        Mockito.when(entity.getUniqueId()).thenReturn(ENTITY_ID);
        Mockito.when(entity.getType()).thenReturn(EntityType.ZOMBIE);

        var event = Mockito.mock(CauldronLevelChangeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getEntity()).thenReturn(entity);
        Mockito.when(event.getReason()).thenReturn(
            CauldronLevelChangeEvent.ChangeReason.EXTINGUISH
        );
        Mockito.when(event.getNewState()).thenReturn(changed);

        listener.capture(event);
        listener.finalizeEvent(event);

        var submission = api.submissions.remove();
        Assertions.assertNull(submission.subject());
        Assertions.assertEquals(
            cauldronPayload(
                oldState,
                newState,
                CauldronLevelChangeEvent.ChangeReason.EXTINGUISH,
                "entity",
                ENTITY_ID,
                EntityType.ZOMBIE.name()
            ),
            PaperBlockStatePayloadCodec.decode(submission.payload())
        );
    }

    @Test
    void testNaturalChangeHasReasonAndNoActorMetadata() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperCauldronLevelChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var oldState = Blocks.CAULDRON.defaultBlockState();
        var newState = waterCauldron(1);
        var block = PaperBlockEventTestSupport.block(
            world, 4, 5, 6, oldState, Material.CAULDRON
        );
        var changed = PaperBlockEventTestSupport.state(
            world, block, 4, 5, 6, newState
        );

        var event = Mockito.mock(CauldronLevelChangeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getReason()).thenReturn(
            CauldronLevelChangeEvent.ChangeReason.NATURAL_FILL
        );
        Mockito.when(event.getNewState()).thenReturn(changed);

        listener.capture(event);
        listener.finalizeEvent(event);

        var submission = api.submissions.remove();
        Assertions.assertNull(submission.subject());
        Assertions.assertEquals(
            cauldronPayload(
                oldState,
                newState,
                CauldronLevelChangeEvent.ChangeReason.NATURAL_FILL,
                "none",
                null,
                null
            ),
            PaperBlockStatePayloadCodec.decode(submission.payload())
        );
    }

    @Test
    void testCancelledChangeIsNotSubmitted() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperCauldronLevelChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var block = PaperBlockEventTestSupport.block(
            world, 7, 8, 9, waterCauldron(2), Material.WATER_CAULDRON
        );
        var changed = PaperBlockEventTestSupport.state(
            world, block, 7, 8, 9, waterCauldron(1)
        );
        var event = Mockito.mock(CauldronLevelChangeEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getReason()).thenReturn(
            CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL
        );
        Mockito.when(event.getNewState()).thenReturn(changed);
        Mockito.when(event.isCancelled()).thenReturn(true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static net.minecraft.world.level.block.state.BlockState waterCauldron(int level) {
        return Blocks.WATER_CAULDRON.defaultBlockState().setValue(
            LayeredCauldronBlock.LEVEL,
            level
        );
    }

    private static CompoundTag cauldronPayload(
        net.minecraft.world.level.block.state.BlockState oldState,
        net.minecraft.world.level.block.state.BlockState newState,
        CauldronLevelChangeEvent.ChangeReason reason,
        String actorKind,
        UUID entityId,
        String entityType
    ) {
        var payload = new CompoundTag();
        payload.put("old_state", NbtUtils.writeBlockState(oldState));
        payload.put("new_state", NbtUtils.writeBlockState(newState));
        payload.putString("reason", reason.name());
        payload.putString("actor_kind", actorKind);
        if (entityId != null) {
            payload.putString("actor_entity_uuid", entityId.toString());
        }
        if (entityType != null) {
            payload.putString("actor_entity_type", entityType);
        }
        return payload;
    }
}
