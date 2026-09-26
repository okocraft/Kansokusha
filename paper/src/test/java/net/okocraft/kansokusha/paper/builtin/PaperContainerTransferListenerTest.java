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
            2,
            PaperItemStackPayloadCodec.decode(payload.getCompoundOrEmpty("item")).getAmount()
        );

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
