package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

class PaperBlockBreakListenerTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-22T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
    }

    @Test
    void testNonCancelledBreakSubmitsLowestSnapshot() throws Exception {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any())).thenReturn(RegistrationOutcome.REGISTERED);
        Mockito.when(api.submit(Mockito.any())).thenReturn(SubmissionOutcome.ACCEPTED);

        var listener = PaperBlockBreakListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var state = Blocks.DIAMOND_ORE.defaultBlockState();
        var event = event(state.asBlockData(), false);

        listener.capture(event);
        listener.finalizeEvent(event);

        var captor = ArgumentCaptor.forClass(EventSubmission.class);
        Mockito.verify(api).submit(captor.capture());

        var submission = captor.getValue();
        Assertions.assertEquals(PaperBlockBreakListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(SERVER_KEY, submission.serverKey());
        Assertions.assertEquals(Key.key("example", "world"), submission.worldKey());
        Assertions.assertEquals(new BlockPosition(12, 64, -7), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        Assertions.assertEquals(
            NbtUtils.writeBlockState(state),
            PaperBlockStatePayloadCodec.decode(submission.payload())
        );
        Assertions.assertEquals(0, listener.inFlightCount());

        Mockito.verify(event.getBlock(), Mockito.times(1)).getBlockData();
    }

    @Test
    void testCancelledBreakDropsAndRemovesSnapshot() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any())).thenReturn(RegistrationOutcome.REGISTERED);

        var listener = PaperBlockBreakListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var event = event(Blocks.STONE.defaultBlockState().asBlockData(), true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Mockito.verify(api, Mockito.never()).submit(Mockito.any());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testClearInFlightStateDropsCapturedSnapshot() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any())).thenReturn(RegistrationOutcome.REGISTERED);

        var listener = PaperBlockBreakListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var event = event(Blocks.STONE.defaultBlockState().asBlockData(), false);

        listener.capture(event);
        Assertions.assertEquals(1, listener.inFlightCount());

        listener.clearInFlightState();
        listener.finalizeEvent(event);

        Mockito.verify(api, Mockito.never()).submit(Mockito.any());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testRegistrationConflictFailsBeforeListenerCreation() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any())).thenReturn(RegistrationOutcome.CONFLICT);

        var failure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> PaperBlockBreakListener.register(api, SERVER_KEY)
        );

        Assertions.assertTrue(failure.getMessage().contains("kansokusha:block_break"));
    }

    @Test
    void testConcurrentFoliaStyleEventsDoNotCrossSnapshots() throws Exception {
        var api = new RecordingApi();
        var listener = PaperBlockBreakListener.register(api, SERVER_KEY);
        var executor = Executors.newFixedThreadPool(8);
        var events = new ArrayList<BlockBreakEvent>();
        var expectedStates = new HashMap<Integer, net.minecraft.world.level.block.state.BlockState>();

        for (int i = 0; i < 64; i++) {
            var x = 1000 + i;
            var state = (i & 1) == 0
                ? Blocks.DEEPSLATE.defaultBlockState()
                : Blocks.DIAMOND_ORE.defaultBlockState();
            events.add(event(state.asBlockData(), false, x, 64, -i));
            expectedStates.put(x, state);
        }

        try {
            var captureTasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (var event : events) {
                captureTasks.add(executor.submit(() -> listener.capture(event)));
            }
            for (var task : captureTasks) {
                task.get();
            }

            Assertions.assertEquals(64, listener.inFlightCount());

            var finalizeTasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (var event : events) {
                finalizeTasks.add(executor.submit(() -> listener.finalizeEvent(event)));
            }
            for (var task : finalizeTasks) {
                task.get();
            }
        } finally {
            executor.shutdown();
            Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        Assertions.assertEquals(64, api.submissions.size());
        Assertions.assertEquals(0, listener.inFlightCount());

        var submissionsByX = new HashMap<Integer, EventSubmission>();
        for (var submission : api.submissions) {
            var position = submission.position();
            Assertions.assertNotNull(position);
            Assertions.assertNull(
                submissionsByX.put(position.x(), submission),
                "Duplicate submission for x=" + position.x()
            );
        }

        for (int i = 0; i < 64; i++) {
            var x = 1000 + i;
            var submission = submissionsByX.get(x);
            Assertions.assertNotNull(submission, "Missing submission for x=" + x);
            Assertions.assertEquals(new BlockPosition(x, 64, -i), submission.position());
            Assertions.assertEquals(
                NbtUtils.writeBlockState(expectedStates.get(x)),
                PaperBlockStatePayloadCodec.decode(submission.payload())
            );
        }
    }

    private static BlockBreakEvent event(
        org.bukkit.block.data.BlockData blockData,
        boolean cancelled
    ) {
        return event(blockData, cancelled, 12, 64, -7);
    }

    private static BlockBreakEvent event(
        org.bukkit.block.data.BlockData blockData,
        boolean cancelled,
        int x,
        int y,
        int z
    ) {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));

        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(y);
        Mockito.when(block.getZ()).thenReturn(z);
        Mockito.when(block.getBlockData()).thenReturn(blockData);

        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);

        var event = Mockito.mock(BlockBreakEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        return event;
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
