package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class PaperContainerPickupListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID ITEM_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174123");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testWorldItemPickupSnapshotsEntityAndUsesInventoryLocation() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var inventory = Mockito.mock(Inventory.class);
        Mockito.when(inventory.getType()).thenReturn(InventoryType.HOPPER);
        Mockito.when(inventory.getSize()).thenReturn(5);
        Mockito.when(inventory.getHolder()).thenReturn(null);
        Mockito.when(inventory.getLocation()).thenReturn(new Location(world, 10.5, 65, 20.5));

        var stack = ItemStack.of(Material.EMERALD, 3);
        var item = Mockito.mock(Item.class);
        Mockito.when(item.getUniqueId()).thenReturn(ITEM_ID);
        Mockito.when(item.getItemStack()).thenReturn(stack);
        Mockito.when(item.getLocation()).thenReturn(new Location(world, 11.25, 65.5, 20.75));

        var event = Mockito.mock(InventoryPickupItemEvent.class);
        Mockito.when(event.getInventory()).thenReturn(inventory);
        Mockito.when(event.getItem()).thenReturn(item);
        Mockito.when(event.isCancelled()).thenReturn(false);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.remove();
        Assertions.assertEquals(PaperContainerPickupListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(Key.key("example", "world"), submission.worldKey());
        Assertions.assertEquals(new BlockPosition(10, 65, 20), submission.position());
        Assertions.assertNull(submission.actor());

        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            "non_cancelled_container_pickup_attempt",
            string(payload, "semantics")
        );
        Assertions.assertEquals("world_item", string(payload, "source_kind"));
        Assertions.assertEquals(ITEM_ID.toString(), string(payload, "item_entity_uuid"));
        Assertions.assertEquals(
            3,
            PaperItemStackPayloadCodec.decode(
                payload.getCompoundOrEmpty("source_item")
            ).getAmount()
        );
        var origin = payload.getCompoundOrEmpty("item_origin");
        Assertions.assertEquals("example:world", string(origin, "world"));
        Assertions.assertTrue(origin.contains("x"));
        Assertions.assertTrue(origin.contains("y"));
        Assertions.assertTrue(origin.contains("z"));

        Mockito.verify(item, Mockito.times(1)).getItemStack();
        Mockito.verify(item, Mockito.times(1)).getLocation();
    }

    @Test
    void testCancelledPickupDoesNotSubmit() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var inventory = Mockito.mock(Inventory.class);
        Mockito.when(inventory.getType()).thenReturn(InventoryType.HOPPER);
        Mockito.when(inventory.getSize()).thenReturn(5);
        Mockito.when(inventory.getHolder()).thenReturn(null);
        Mockito.when(inventory.getLocation()).thenReturn(null);

        var item = Mockito.mock(Item.class);
        Mockito.when(item.getUniqueId()).thenReturn(ITEM_ID);
        Mockito.when(item.getItemStack()).thenReturn(ItemStack.of(Material.IRON_INGOT, 1));
        Mockito.when(item.getLocation()).thenReturn(new Location(world, 4, 70, 8));

        var event = Mockito.mock(InventoryPickupItemEvent.class);
        Mockito.when(event.getInventory()).thenReturn(inventory);
        Mockito.when(event.getItem()).thenReturn(item);
        Mockito.when(event.isCancelled()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static PaperContainerPickupListener listener(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        return PaperContainerPickupListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static String string(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getString(key).orElseThrow();
    }
}
