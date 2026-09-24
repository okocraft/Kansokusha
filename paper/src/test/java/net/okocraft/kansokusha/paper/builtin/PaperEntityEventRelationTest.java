package net.okocraft.kansokusha.paper.builtin;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

class PaperEntityEventRelationTest {

    private static final UUID TARGET_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174120");
    private static final UUID ACTOR_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174121");

    @Test
    void testHangingPlacementOwnsCanonicalSubmissionIfGenericCallbackAlsoOccurs()
        throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityPlaceListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        var world = PaperBlockEventTestSupport.world();
        var hanging = hanging(world);
        var player = player(world);
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getX()).thenReturn(4);
        Mockito.when(block.getY()).thenReturn(65);
        Mockito.when(block.getZ()).thenReturn(4);

        var hangingEvent = Mockito.mock(HangingPlaceEvent.class);
        Mockito.when(hangingEvent.getEntity()).thenReturn(hanging);
        Mockito.when(hangingEvent.getPlayer()).thenReturn(player);
        Mockito.when(hangingEvent.getBlock()).thenReturn(block);
        Mockito.when(hangingEvent.getBlockFace()).thenReturn(BlockFace.NORTH);
        Mockito.when(hangingEvent.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(hangingEvent.getItemStack()).thenReturn(
            ItemStack.of(org.bukkit.Material.ITEM_FRAME, 1)
        );

        var genericEvent = Mockito.mock(EntityPlaceEvent.class);
        Mockito.when(genericEvent.getEntity()).thenReturn(hanging);

        listener.captureHanging(hangingEvent);
        listener.captureGeneric(genericEvent);
        listener.finalizeHanging(hangingEvent);
        listener.finalizeGeneric(genericEvent);

        Assertions.assertEquals(1, api.submissions.size());
        var payload = PaperPayloadNbtCodec.decode(api.submissions.remove().payload());
        Assertions.assertEquals(
            PaperEntityPlaceListener.HANGING_SOURCE_EVENT,
            payload.getString("source_event").orElseThrow()
        );
        Mockito.verify(genericEvent, Mockito.never()).getPlayer();
    }

    @Test
    void testPaperHangingBreakSequenceProducesOnlyHangingCanonicalSubmission()
        throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityBreakListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        enableGenericCallbacks(listener);
        var world = PaperBlockEventTestSupport.world();
        var hanging = hanging(world);
        var remover = player(world);
        var damageSource = damageSource();

        var hangingEvent = Mockito.mock(HangingBreakByEntityEvent.class);
        Mockito.when(hangingEvent.getEntity()).thenReturn(hanging);
        Mockito.when(hangingEvent.getRemover()).thenReturn(remover);
        Mockito.when(hangingEvent.getDamageSource()).thenReturn(damageSource);
        Mockito.when(hangingEvent.getCause()).thenReturn(HangingBreakEvent.RemoveCause.ENTITY);

        var genericEvent = new PaperGenericEntityBreakEventFixture(
            hanging,
            remover,
            damageSource,
            PaperGenericEntityBreakEventFixture.RemoveCause.ENTITY
        );

        // Paper 26.3 fires HangingBreakByEntityEvent first and then the generic
        // EntityBreakByEntityEvent for the same hanging damage path.
        listener.captureHanging(hangingEvent);
        listener.finalizeHanging(hangingEvent);
        listener.captureGeneric(genericEvent);
        listener.finalizeGeneric(genericEvent);

        Assertions.assertEquals(1, api.submissions.size());
        var payload = PaperPayloadNbtCodec.decode(api.submissions.remove().payload());
        Assertions.assertEquals(
            PaperEntityBreakListener.HANGING_SOURCE_EVENT,
            payload.getString("source_event").orElseThrow()
        );
        Assertions.assertEquals(1, genericEvent.removerReads());
        Mockito.verify(hangingEvent, Mockito.never()).getRemover();
    }

    @Test
    void testGenericCancellationControlsHangingBreakSubmission() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityBreakListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY
        );
        enableGenericCallbacks(listener);
        var world = PaperBlockEventTestSupport.world();
        var hanging = hanging(world);
        var remover = player(world);
        var damageSource = damageSource();

        var hangingEvent = Mockito.mock(HangingBreakByEntityEvent.class);
        var genericEvent = new PaperGenericEntityBreakEventFixture(
            hanging,
            remover,
            damageSource,
            PaperGenericEntityBreakEventFixture.RemoveCause.ENTITY
        );

        listener.captureHanging(hangingEvent);
        listener.finalizeHanging(hangingEvent);
        listener.captureGeneric(genericEvent);
        genericEvent.setCancelled(true);
        listener.finalizeGeneric(genericEvent);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testNaturalHangingBreakHasNoListenerEntryPoint() {
        Assertions.assertDoesNotThrow(() ->
            PaperEntityBreakListener.class.getMethod(
                "captureHanging",
                HangingBreakByEntityEvent.class
            )
        );
        Assertions.assertThrows(
            NoSuchMethodException.class,
            () -> PaperEntityBreakListener.class.getMethod(
                "captureHanging",
                HangingBreakEvent.class
            )
        );
    }

    private static void enableGenericCallbacks(PaperEntityBreakListener listener) throws Exception {
        var field = PaperEntityBreakListener.class.getDeclaredField("genericCallbacksRegistered");
        field.setAccessible(true);
        field.setBoolean(listener, true);
    }

    private static Hanging hanging(World world) {
        var hanging = Mockito.mock(Hanging.class);
        Mockito.when(hanging.getUniqueId()).thenReturn(TARGET_ID);
        Mockito.when(hanging.getType()).thenReturn(EntityType.ITEM_FRAME);
        Mockito.when(hanging.getWorld()).thenReturn(world);
        Mockito.when(hanging.getLocation()).thenReturn(new Location(world, 4.5, 65, 4.5));
        return hanging;
    }

    private static Player player(World world) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(ACTOR_ID);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);
        Mockito.when(player.getWorld()).thenReturn(world);
        Mockito.when(player.getLocation()).thenReturn(new Location(world, 4, 65, 5));
        return player;
    }

    private static DamageSource damageSource() {
        var source = Mockito.mock(DamageSource.class);
        Mockito.when(source.getDamageType()).thenReturn(DamageType.PLAYER_ATTACK);
        return source;
    }
}
