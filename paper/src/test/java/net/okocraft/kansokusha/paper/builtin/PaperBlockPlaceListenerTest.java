package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class PaperBlockPlaceListenerTest {

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
    void testSinglePlaceUsesLowestImmutableSnapshot() throws Exception {
        var api = new RecordingApi();
        var listener = PaperBlockPlaceListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
        var replaced = Blocks.WATER.defaultBlockState();
        var placed = Blocks.OAK_LOG.defaultBlockState();
        var fixture = singleEvent(replaced, placed, 12, 64, -7, false, true);

        listener.capture(fixture.event());

        Mockito.when(fixture.replacedState().getBlockData())
            .thenReturn(Blocks.LAVA.defaultBlockState().asBlockData());
        Mockito.when(fixture.placedBlock().getBlockData())
            .thenReturn(Blocks.AIR.defaultBlockState().asBlockData());

        listener.finalizeEvent(fixture.event());

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.element();
        Assertions.assertEquals(PaperBlockPlaceListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(SERVER_KEY, submission.serverKey());
        Assertions.assertEquals(Key.key("example", "world"), submission.worldKey());
        Assertions.assertEquals(new BlockPosition(12, 64, -7), submission.position());
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        Assertions.assertEquals(
            placePayload(replaced, placed),
            PaperBlockStatePayloadCodec.decode(submission.payload())
        );
        Assertions.assertEquals(0, listener.inFlightCount());
        Mockito.verify(fixture.replacedState(), Mockito.times(1)).getBlockData();
        Mockito.verify(fixture.placedBlock(), Mockito.times(1)).getBlockData();
    }

    @Test
    void testCancelledAndCannotBuildPlacementsAreDropped() {
        var api = new RecordingApi();
        var listener = PaperBlockPlaceListener.register(api, SERVER_KEY);

        var cancelled = singleEvent(
            Blocks.STONE.defaultBlockState(),
            Blocks.OAK_PLANKS.defaultBlockState(),
            1,
            64,
            1,
            true,
            true
        );
        var cannotBuild = singleEvent(
            Blocks.DIRT.defaultBlockState(),
            Blocks.COBBLESTONE.defaultBlockState(),
            2,
            64,
            2,
            false,
            false
        );

        listener.capture(cancelled.event());
        listener.capture(cannotBuild.event());
        Assertions.assertEquals(2, listener.inFlightCount());

        listener.finalizeEvent(cancelled.event());
        listener.finalizeEvent(cannotBuild.event());

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testMultiPlaceProducesExactlyOneAttemptPerChangedBlock() throws Exception {
        var api = new RecordingApi(
            SubmissionOutcome.ACCEPTED,
            SubmissionOutcome.INGESTION_UNAVAILABLE,
            SubmissionOutcome.ACCEPTED
        );
        var listener = PaperBlockPlaceListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );

        var replacedStates = List.of(
            Blocks.AIR.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            Blocks.STONE.defaultBlockState()
        );
        var placedStates = List.of(
            Blocks.OAK_PLANKS.defaultBlockState(),
            Blocks.OAK_LOG.defaultBlockState(),
            Blocks.GLASS.defaultBlockState()
        );
        var event = multiEvent(replacedStates, placedStates, false, true);

        listener.capture(event);
        listener.finalizeEvent(event);

        Assertions.assertEquals(3, api.submissions.size());
        Assertions.assertEquals(
            List.of(
                SubmissionOutcome.ACCEPTED,
                SubmissionOutcome.INGESTION_UNAVAILABLE,
                SubmissionOutcome.ACCEPTED
            ),
            api.returnedOutcomes
        );
        Assertions.assertEquals(0, listener.inFlightCount());

        var byX = new HashMap<Integer, EventSubmission>();
        for (var submission : api.submissions) {
            Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
            var position = submission.position();
            Assertions.assertNotNull(position);
            Assertions.assertNull(byX.put(position.x(), submission));
        }

        for (int i = 0; i < 3; i++) {
            var x = 100 + i;
            var submission = byX.get(x);
            Assertions.assertNotNull(submission);
            Assertions.assertEquals(new BlockPosition(x, 70, -i), submission.position());
            Assertions.assertEquals(
                placePayload(replacedStates.get(i), placedStates.get(i)),
                PaperBlockStatePayloadCodec.decode(submission.payload())
            );
        }
    }

    @Test
    void testConcurrentFoliaStyleEventsDoNotCrossSnapshots() throws Exception {
        var api = new RecordingApi();
        var listener = PaperBlockPlaceListener.register(api, SERVER_KEY);
        var executor = Executors.newFixedThreadPool(8);
        var fixtures = new ArrayList<SingleFixture>();

        for (int i = 0; i < 32; i++) {
            var replaced = (i & 1) == 0
                ? Blocks.WATER.defaultBlockState()
                : Blocks.STONE.defaultBlockState();
            var placed = (i & 1) == 0
                ? Blocks.OAK_LOG.defaultBlockState()
                : Blocks.DIAMOND_BLOCK.defaultBlockState();
            fixtures.add(singleEvent(replaced, placed, 1000 + i, 80, -i, false, true));
        }

        try {
            var captureTasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (var fixture : fixtures) {
                captureTasks.add(executor.submit(() -> listener.capture(fixture.event())));
            }
            for (var task : captureTasks) {
                task.get();
            }
            Assertions.assertEquals(32, listener.inFlightCount());

            var finalizeTasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (var fixture : fixtures) {
                finalizeTasks.add(executor.submit(() -> listener.finalizeEvent(fixture.event())));
            }
            for (var task : finalizeTasks) {
                task.get();
            }
        } finally {
            executor.shutdown();
            Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        Assertions.assertEquals(32, api.submissions.size());
        Assertions.assertEquals(0, listener.inFlightCount());

        var byX = new HashMap<Integer, EventSubmission>();
        for (var submission : api.submissions) {
            var position = submission.position();
            Assertions.assertNotNull(position);
            Assertions.assertNull(byX.put(position.x(), submission));
        }

        for (int i = 0; i < fixtures.size(); i++) {
            var fixture = fixtures.get(i);
            var x = 1000 + i;
            var submission = byX.get(x);
            Assertions.assertNotNull(submission);
            Assertions.assertEquals(new BlockPosition(x, 80, -i), submission.position());
            Assertions.assertEquals(
                placePayload(fixture.replaced(), fixture.placed()),
                PaperBlockStatePayloadCodec.decode(submission.payload())
            );
        }
    }

    private static SingleFixture singleEvent(
        BlockState replaced,
        BlockState placed,
        int x,
        int y,
        int z,
        boolean cancelled,
        boolean canBuild
    ) {
        var world = world();
        var placedBlock = block(world, placed, x, y, z);
        var replacedState = Mockito.mock(org.bukkit.block.BlockState.class);
        Mockito.when(replacedState.getWorld()).thenReturn(world);
        Mockito.when(replacedState.getX()).thenReturn(x);
        Mockito.when(replacedState.getY()).thenReturn(y);
        Mockito.when(replacedState.getZ()).thenReturn(z);
        Mockito.when(replacedState.getBlock()).thenReturn(placedBlock);
        Mockito.when(replacedState.getBlockData()).thenReturn(replaced.asBlockData());

        var event = Mockito.mock(BlockPlaceEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player());
        Mockito.when(event.getBlockReplacedState()).thenReturn(replacedState);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        Mockito.when(event.canBuild()).thenReturn(canBuild);

        return new SingleFixture(event, replacedState, placedBlock, replaced, placed);
    }

    private static BlockMultiPlaceEvent multiEvent(
        List<BlockState> replacedStates,
        List<BlockState> placedStates,
        boolean cancelled,
        boolean canBuild
    ) {
        var world = world();
        var BukkitStates = new ArrayList<org.bukkit.block.BlockState>();

        for (int i = 0; i < replacedStates.size(); i++) {
            var x = 100 + i;
            var placedBlock = block(world, placedStates.get(i), x, 70, -i);
            var replacedState = Mockito.mock(org.bukkit.block.BlockState.class);
            Mockito.when(replacedState.getWorld()).thenReturn(world);
            Mockito.when(replacedState.getX()).thenReturn(x);
            Mockito.when(replacedState.getY()).thenReturn(70);
            Mockito.when(replacedState.getZ()).thenReturn(-i);
            Mockito.when(replacedState.getBlock()).thenReturn(placedBlock);
            Mockito.when(replacedState.getBlockData())
                .thenReturn(replacedStates.get(i).asBlockData());
            BukkitStates.add(replacedState);
        }

        var event = Mockito.mock(BlockMultiPlaceEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player());
        Mockito.when(event.getReplacedBlockStates()).thenReturn(BukkitStates);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        Mockito.when(event.canBuild()).thenReturn(canBuild);
        return event;
    }

    private static World world() {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));
        return world;
    }

    private static Block block(World world, BlockState state, int x, int y, int z) {
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(y);
        Mockito.when(block.getZ()).thenReturn(z);
        Mockito.when(block.getBlockData()).thenReturn(state.asBlockData());
        return block;
    }

    private static Player player() {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        return player;
    }

    private static CompoundTag placePayload(BlockState replaced, BlockState placed) {
        var expected = new CompoundTag();
        expected.put("replaced", NbtUtils.writeBlockState(replaced));
        expected.put("placed", NbtUtils.writeBlockState(placed));
        return expected;
    }

    private record SingleFixture(
        BlockPlaceEvent event,
        org.bukkit.block.BlockState replacedState,
        Block placedBlock,
        BlockState replaced,
        BlockState placed
    ) {
    }

    private static final class RecordingApi implements KansokushaApi {

        private final ConcurrentLinkedQueue<EventSubmission> submissions =
            new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<SubmissionOutcome> outcomes =
            new ConcurrentLinkedQueue<>();
        private final List<SubmissionOutcome> returnedOutcomes =
            java.util.Collections.synchronizedList(new ArrayList<>());

        private RecordingApi(SubmissionOutcome... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

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
            var outcome = this.outcomes.poll();
            if (outcome == null) {
                outcome = SubmissionOutcome.ACCEPTED;
            }
            this.returnedOutcomes.add(outcome);
            return outcome;
        }
    }
}
