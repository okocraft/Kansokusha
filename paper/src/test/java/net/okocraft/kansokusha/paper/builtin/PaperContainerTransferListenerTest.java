package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class PaperContainerTransferListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testNonCancelledTransferRecordsAttemptWithoutClaimingDestinationSuccess() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var source = inventory(
            InventoryType.HOPPER,
            5,
            new Location(world, 1.25, 64, -3.25)
        );
        var destination = inventory(InventoryType.CHEST, 27, null);
        var item = ItemStack.of(Material.DIAMOND, 2);
        var event = Mockito.mock(InventoryMoveItemEvent.class);
        Mockito.when(event.getSource()).thenReturn(source);
        Mockito.when(event.getDestination()).thenReturn(destination);
        Mockito.when(event.getInitiator()).thenReturn(source);
        Mockito.when(event.getItem()).thenReturn(item);
        Mockito.when(event.isCancelled()).thenReturn(false);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty(), "The transfer is buffered until its window ends.");
        listener.flushAll();

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperContainerTransferListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(Key.key("example", "world"), submission.worldKey());
        Assertions.assertEquals(new BlockPosition(1, 64, -4), submission.position());
        Assertions.assertNull(submission.actor());

        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("source", string(payload, "initiator_role"));
        Assertions.assertFalse(payload.contains("initiator_inventory"));
        Assertions.assertEquals(
            "hopper",
            string(payload.getCompoundOrEmpty("source_inventory"), "type")
        );
        Assertions.assertEquals(
            "chest",
            string(payload.getCompoundOrEmpty("destination_inventory"), "type")
        );
        Assertions.assertFalse(payload.contains("initial_item"));
        Assertions.assertEquals(
            1,
            PaperItemStackPayloadCodec.decode(payload.getCompoundOrEmpty("item")).getAmount()
        );
        Assertions.assertEquals(1, payload.getIntOr("attempt_count", 0));
        Assertions.assertEquals(2L, payload.getLongOr("item_amount", 0L));

        Mockito.verify(destination, Mockito.never()).getContents();
    }

    @Test
    void testCancelledTransferAndMissingLocationsStayAtEventBoundary() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var source = inventory(InventoryType.HOPPER, 5, null);
        var destination = inventory(InventoryType.CHEST, 27, null);
        var event = Mockito.mock(InventoryMoveItemEvent.class);
        Mockito.when(event.getSource()).thenReturn(source);
        Mockito.when(event.getDestination()).thenReturn(destination);
        Mockito.when(event.getInitiator()).thenReturn(destination);
        Mockito.when(event.getItem()).thenReturn(ItemStack.of(Material.STONE, 1));
        Mockito.when(event.isCancelled()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testRepeatedTransfersAreAggregatedPerWindow() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var clock = new MutableClock(OCCURRED_AT);
        var listener = PaperContainerTransferListener.register(api, PaperBlockEventTestSupport.SERVER_KEY, clock);
        var world = PaperBlockEventTestSupport.world();
        var source = inventory(InventoryType.HOPPER, 5, new Location(world, 1.25, 64, -3.25));
        var destination = inventory(InventoryType.CHEST, 27, null);

        for (var i = 0; i < 3; i++) {
            PaperListenerTestSupport.fire(listener, transfer(source, destination, ItemStack.of(Material.DIAMOND, 1)));
        }
        PaperListenerTestSupport.fire(listener, transfer(source, destination, ItemStack.of(Material.EMERALD, 2)));
        PaperListenerTestSupport.fire(listener, transfer(destination, source, ItemStack.of(Material.DIAMOND, 1)));

        listener.flushDue();
        Assertions.assertTrue(api.submissions.isEmpty(), "The window has not ended yet.");

        clock.instant = OCCURRED_AT.plus(PaperContainerTransferListener.CONTAINER_WINDOW);
        listener.flushDue();

        Assertions.assertEquals(3, api.submissions.size(), "Different items and directions stay separate.");
        var diamond = api.submissions.stream()
            .filter(submission -> submission.targetType().equals(Key.key("minecraft", "diamond")))
            .filter(submission -> {
                try {
                    return string(PaperPayloadNbtCodec.decode(submission.payload()).getCompoundOrEmpty("source_inventory"), "type").equals("hopper");
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
            })
            .findFirst().orElseThrow();
        Assertions.assertEquals(OCCURRED_AT, diamond.occurredAt(), "The event is stamped with the window start.");
        var payload = PaperPayloadNbtCodec.decode(diamond.payload());
        Assertions.assertEquals(3, payload.getIntOr("attempt_count", 0));
        Assertions.assertEquals(3L, payload.getLongOr("item_amount", 0L));

        api.submissions.clear();
        listener.flushAll();
        Assertions.assertTrue(api.submissions.isEmpty(), "Flushed transfers are not submitted twice.");

        PaperListenerTestSupport.fire(listener, transfer(source, destination, ItemStack.of(Material.DIAMOND, 1)));
        clock.instant = clock.instant.plus(PaperContainerTransferListener.CONTAINER_WINDOW);
        PaperListenerTestSupport.fire(listener, transfer(source, destination, ItemStack.of(Material.DIAMOND, 1)));
        Assertions.assertEquals(1, api.submissions.size(), "A transfer after the window closes the previous one.");
        listener.flushAll();
        Assertions.assertEquals(2, api.submissions.size());
    }

    @Test
    void testTransfersBetweenHoppersUseTheLongWindow() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var clock = new MutableClock(OCCURRED_AT);
        var listener = PaperContainerTransferListener.register(api, PaperBlockEventTestSupport.SERVER_KEY, clock);
        var world = PaperBlockEventTestSupport.world();
        var upper = inventory(InventoryType.HOPPER, 5, new Location(world, 1.5, 65, 1.5));
        var lower = inventory(InventoryType.HOPPER, 5, new Location(world, 1.5, 64, 1.5));
        var chest = inventory(InventoryType.CHEST, 27, new Location(world, 1.5, 63, 1.5));

        PaperListenerTestSupport.fire(listener, transfer(upper, lower, ItemStack.of(Material.DIAMOND, 1)));
        PaperListenerTestSupport.fire(listener, transfer(lower, chest, ItemStack.of(Material.DIAMOND, 1)));

        clock.instant = OCCURRED_AT.plus(PaperContainerTransferListener.CONTAINER_WINDOW);
        listener.flushDue();
        Assertions.assertEquals(1, api.submissions.size(), "Only the move into the chest uses the short window.");
        api.submissions.clear();

        clock.instant = OCCURRED_AT.plus(PaperContainerTransferListener.CHAIN_WINDOW);
        listener.flushDue();
        Assertions.assertEquals(1, api.submissions.size(), "The hopper-to-hopper move is submitted after the long window.");
    }

    private static InventoryMoveItemEvent transfer(Inventory source, Inventory destination, ItemStack item) {
        var event = Mockito.mock(InventoryMoveItemEvent.class);
        Mockito.when(event.getSource()).thenReturn(source);
        Mockito.when(event.getDestination()).thenReturn(destination);
        Mockito.when(event.getInitiator()).thenReturn(source);
        Mockito.when(event.getItem()).thenReturn(item);
        Mockito.when(event.isCancelled()).thenReturn(false);
        return event;
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }
    }

    private static PaperContainerTransferListener listener(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        return PaperContainerTransferListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static Inventory inventory(
        InventoryType type,
        int size,
        Location location
    ) {
        var inventory = Mockito.mock(Inventory.class);
        Mockito.when(inventory.getType()).thenReturn(type);
        Mockito.when(inventory.getSize()).thenReturn(size);
        Mockito.when(inventory.getHolder()).thenReturn(null);
        Mockito.when(inventory.getLocation()).thenReturn(location);
        return inventory;
    }

    private static String string(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getString(key).orElseThrow();
    }
}
