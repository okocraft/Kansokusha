package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class PaperFlowerPotChangeListenerTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testInsertAndRemoveRecordBeforeAndAfterContents() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var insertItem = ItemStack.of(Material.POPPY, 64);
        var insert = event(10, insertItem, true, false);
        var removeItem = ItemStack.of(Material.DANDELION, 1);
        var remove = event(20, removeItem, false, false);

        listener.capture(insert.event());
        insertItem.setAmount(32);
        listener.finalizeEvent(insert.event());
        listener.capture(remove.event());
        removeItem.setAmount(2);
        listener.finalizeEvent(remove.event());

        var byX = submissionsByX(api);
        var insertPayload = PaperAdditionalBuiltInPayloadCodec.decode(byX.get(10).payload());
        Assertions.assertEquals("insert", string(insertPayload, "action"));
        Assertions.assertTrue(
            PaperItemStackPayloadCodec.decode(
                PaperAdditionalBuiltInPayloadCodec.decodeNestedItem(insertPayload, "before")
            ).isEmpty()
        );
        var inserted = PaperItemStackPayloadCodec.decode(
            PaperAdditionalBuiltInPayloadCodec.decodeNestedItem(insertPayload, "after")
        );
        Assertions.assertEquals(Material.POPPY, inserted.getType());
        Assertions.assertEquals(1, inserted.getAmount());

        var removePayload = PaperAdditionalBuiltInPayloadCodec.decode(byX.get(20).payload());
        Assertions.assertEquals("remove", string(removePayload, "action"));
        var removed = PaperItemStackPayloadCodec.decode(
            PaperAdditionalBuiltInPayloadCodec.decodeNestedItem(removePayload, "before")
        );
        Assertions.assertEquals(Material.DANDELION, removed.getType());
        Assertions.assertEquals(1, removed.getAmount());
        Assertions.assertTrue(
            PaperItemStackPayloadCodec.decode(
                PaperAdditionalBuiltInPayloadCodec.decodeNestedItem(removePayload, "after")
            ).isEmpty()
        );
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testCancelledFlowerPotChangeDoesNotSubmit() {
        var api = new RecordingApi();
        var listener = listener(api);
        var fixture = event(1, ItemStack.of(Material.POPPY, 1), true, true);

        listener.capture(fixture.event());
        listener.finalizeEvent(fixture.event());

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testRegistrationConflictFails() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any())).thenReturn(RegistrationOutcome.CONFLICT);

        var failure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> PaperFlowerPotChangeListener.register(api, SERVER_KEY)
        );

        Assertions.assertTrue(failure.getMessage().contains("kansokusha:flower_pot_change"));
    }

    @Test
    void testConcurrentFoliaStyleFlowerPotEventsDoNotCrossSnapshots() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var fixtures = new ArrayList<Fixture>();
        for (int i = 0; i < 32; i++) {
            fixtures.add(event(
                1000 + i,
                ItemStack.of((i & 1) == 0 ? Material.POPPY : Material.DANDELION, 1),
                (i & 1) == 0,
                false
            ));
        }

        var executor = Executors.newFixedThreadPool(8);
        try {
            var captures = fixtures.stream()
                .map(f -> executor.submit(() -> listener.capture(f.event())))
                .toList();
            for (var task : captures) {
                task.get();
            }
            var finalizers = fixtures.stream()
                .map(f -> executor.submit(() -> listener.finalizeEvent(f.event())))
                .toList();
            for (var task : finalizers) {
                task.get();
            }
        } finally {
            executor.shutdown();
            Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        var byX = submissionsByX(api);
        Assertions.assertEquals(fixtures.size(), byX.size());
        for (int i = 0; i < fixtures.size(); i++) {
            var payload = PaperAdditionalBuiltInPayloadCodec.decode(byX.get(1000 + i).payload());
            Assertions.assertEquals((i & 1) == 0 ? "insert" : "remove", string(payload, "action"));
        }
        Assertions.assertEquals(0, listener.inFlightCount());
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
        public RegistrationOutcome registerEventType(EventTypeDefinition definition) {
            return RegistrationOutcome.REGISTERED;
        }

        @Override
        public SubmissionOutcome submit(EventSubmission submission) {
            this.submissions.add(submission);
            return SubmissionOutcome.ACCEPTED;
        }
    }
}
