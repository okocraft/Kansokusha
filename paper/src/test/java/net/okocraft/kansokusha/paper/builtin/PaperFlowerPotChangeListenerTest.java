package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import net.kyori.adventure.key.Key;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

class PaperFlowerPotChangeListenerTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testInsertAndRemoveRecordBeforeAndAfterContents() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var nmsInsertItem = new net.minecraft.world.item.ItemStack(Items.POPPY, 64);
        nmsInsertItem.set(
            DataComponents.CUSTOM_NAME,
            net.minecraft.network.chat.Component.literal("not stored by flower pot")
        );
        var insertItem = CraftItemStack.asBukkitCopy(nmsInsertItem);
        var insert = event(10, insertItem, true, false);
        var removeItem = ItemStack.of(Material.DANDELION, 1);
        var remove = event(20, removeItem, false, false);

        PaperListenerTestSupport.fire(listener, insert.event());
        PaperListenerTestSupport.fire(listener, remove.event());

        var byX = submissionsByX(api);
        var insertPayload = PaperPayloadNbtCodec.decode(byX.get(10).payload());
        Assertions.assertEquals("insert", string(insertPayload, "action"));
        Assertions.assertTrue(
            PaperItemStackPayloadCodec.decode(
                insertPayload.getCompoundOrEmpty("before")
            ).isEmpty()
        );
        var inserted = PaperItemStackPayloadCodec.decode(
            insertPayload.getCompoundOrEmpty("after")
        );
        Assertions.assertEquals(Material.POPPY, inserted.getType());
        Assertions.assertEquals(1, inserted.getAmount());
        Assertions.assertNull(
            CraftItemStack.asNMSCopy(inserted).get(DataComponents.CUSTOM_NAME)
        );

        var removePayload = PaperPayloadNbtCodec.decode(byX.get(20).payload());
        Assertions.assertEquals("remove", string(removePayload, "action"));
        var removed = PaperItemStackPayloadCodec.decode(
            removePayload.getCompoundOrEmpty("before")
        );
        Assertions.assertEquals(Material.DANDELION, removed.getType());
        Assertions.assertEquals(1, removed.getAmount());
        Assertions.assertTrue(
            PaperItemStackPayloadCodec.decode(
                removePayload.getCompoundOrEmpty("after")
            ).isEmpty()
        );
    }

    @Test
    void testCancelledFlowerPotChangeDoesNotSubmit() {
        var api = new RecordingApi();
        var listener = listener(api);
        var fixture = event(1, ItemStack.of(Material.POPPY, 1), true, true);

        PaperListenerTestSupport.fire(listener, fixture.event());

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static PaperFlowerPotChangeListener listener(RecordingApi api) {
        return PaperFlowerPotChangeListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static Fixture event(
        int x,
        ItemStack item,
        boolean placing,
        boolean cancelled
    ) {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(65);
        Mockito.when(block.getZ()).thenReturn(-x);
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        var event = Mockito.mock(PlayerFlowerPotManipulateEvent.class);
        Mockito.when(event.getFlowerpot()).thenReturn(block);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getItem()).thenReturn(item);
        Mockito.when(event.isPlacing()).thenReturn(placing);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        return new Fixture(event);
    }

    private static HashMap<Integer, EventSubmission> submissionsByX(RecordingApi api) {
        var byX = new HashMap<Integer, EventSubmission>();
        for (var submission : api.submissions) {
            Assertions.assertNull(byX.put(submission.position().x(), submission));
        }
        return byX;
    }

    private static String string(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getString(key).orElseThrow();
    }

    private record Fixture(PlayerFlowerPotManipulateEvent event) {
    }

    private static final class RecordingApi implements KansokushaApi {

        private final ConcurrentLinkedQueue<EventSubmission> submissions =
            new ConcurrentLinkedQueue<>();

        @Override
        public Optional<Key> localServerKey() {
            return Optional.of(SERVER_KEY);
        }

        @Override
        public void registerEventType(EventTypeDefinition definition) {
        }

        @Override
        public boolean submit(EventSubmission submission) {
            this.submissions.add(submission);
            return true;
        }
    }
}
