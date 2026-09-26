package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.position.BlockPosition;
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
    void testSinglePlaceSubmitsReplacedAndPlacedStates() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var fixture = single(
            Blocks.WATER.defaultBlockState(),
            Blocks.OAK_LOG.defaultBlockState(),
            12, 64, -7, false, true
        );

        PaperListenerTestSupport.fire(listener, fixture.event());

        var submission = Assertions.assertDoesNotThrow(() -> api.submissions.remove());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(new BlockPosition(12, 64, -7), submission.position());
        Assertions.assertEquals(
            placePayload(fixture.replaced(), fixture.placed()),
            PaperPayloadNbtCodec.decode(submission.payload())
        );
        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testCancelledAndCannotBuildPlacementsAreDropped() {
        var api = new RecordingApi();
        var listener = listener(api);
        var cancelled = single(
            Blocks.STONE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(),
            1, 64, 1, true, true
        );
        var cannotBuild = single(
            Blocks.DIRT.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
            2, 64, 2, false, false
        );

        PaperListenerTestSupport.fire(listener, cancelled.event());
        PaperListenerTestSupport.fire(listener, cannotBuild.event());

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testMultiPlaceAttemptsEverySnapshotWithOneTimestamp() throws Exception {
        var api = new RecordingApi(true, false, true);
        var clock = Mockito.mock(Clock.class);
        Mockito.when(clock.instant()).thenReturn(
            OCCURRED_AT,
            OCCURRED_AT.plusSeconds(1),
            OCCURRED_AT.plusSeconds(2)
        );
        var listener = PaperBlockPlaceListener.register(api, SERVER_KEY, clock);
        var replaced = List.of(
            Blocks.AIR.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            Blocks.STONE.defaultBlockState()
        );
        var placed = List.of(
            Blocks.OAK_PLANKS.defaultBlockState(),
            Blocks.OAK_LOG.defaultBlockState(),
            Blocks.GLASS.defaultBlockState()
        );

        var event = multi(replaced, placed);
        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertEquals(3, api.submissions.size());
        Assertions.assertEquals(List.of(true, false, true), api.returnedOutcomes);
        var byX = submissionsByX(api.submissions);
        for (int i = 0; i < 3; i++) {
            var submission = byX.get(100 + i);
            Assertions.assertNotNull(submission);
            Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
            Assertions.assertEquals(new BlockPosition(100 + i, 70, -i), submission.position());
            Assertions.assertEquals(
                placePayload(replaced.get(i), placed.get(i)),
                PaperPayloadNbtCodec.decode(submission.payload())
            );
        }
        Mockito.verify(clock, Mockito.times(1)).instant();
    }

    private static PaperBlockPlaceListener listener(RecordingApi api) {
        return PaperBlockPlaceListener.register(
            api, SERVER_KEY, Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static SingleFixture single(
        BlockState replaced,
        BlockState placed,
        int x,
        int y,
        int z,
        boolean cancelled,
        boolean canBuild
    ) {
        var world = world();
        var placedBlock = block(placed);
        var replacedState = state(world, placedBlock, replaced, x, y, z);
        var player = player();
        var event = Mockito.mock(BlockPlaceEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getBlockReplacedState()).thenReturn(replacedState);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        Mockito.when(event.canBuild()).thenReturn(canBuild);
        return new SingleFixture(event, replaced, placed);
    }

    private static BlockMultiPlaceEvent multi(
        List<BlockState> replaced,
        List<BlockState> placed
    ) {
        var world = world();
        var states = new ArrayList<org.bukkit.block.BlockState>();
        for (int i = 0; i < replaced.size(); i++) {
            var block = block(placed.get(i));
            states.add(state(world, block, replaced.get(i), 100 + i, 70, -i));
        }
        var player = player();
        var event = Mockito.mock(BlockMultiPlaceEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getReplacedBlockStates()).thenReturn(states);
        Mockito.when(event.canBuild()).thenReturn(true);
        return event;
    }

    private static org.bukkit.block.BlockState state(
        World world,
        Block block,
        BlockState state,
        int x,
        int y,
        int z
    ) {
        var result = Mockito.mock(org.bukkit.block.BlockState.class);
        Mockito.when(result.getWorld()).thenReturn(world);
        Mockito.when(result.getBlock()).thenReturn(block);
        Mockito.when(result.getX()).thenReturn(x);
        Mockito.when(result.getY()).thenReturn(y);
        Mockito.when(result.getZ()).thenReturn(z);
        var blockData = state.asBlockData();
        Mockito.when(result.getBlockData()).thenReturn(blockData);
        return result;
    }

    private static World world() {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));
        return world;
    }

    private static Block block(BlockState state) {
        var blockData = state.asBlockData();
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getBlockData()).thenReturn(blockData);
        return block;
    }

    private static Player player() {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        return player;
    }

    private static CompoundTag placePayload(BlockState replaced, BlockState placed) {
        var payload = new CompoundTag();
        payload.put("replaced", NbtUtils.writeBlockState(replaced));
        payload.put("placed", PaperBlockStatePayloadCodec.blockProperties(placed.asBlockData()));
        return payload;
    }

    private static HashMap<Integer, EventSubmission> submissionsByX(
        Iterable<EventSubmission> submissions
    ) {
        var byX = new HashMap<Integer, EventSubmission>();
        for (var submission : submissions) {
            var position = submission.position();
            Assertions.assertNotNull(position);
            Assertions.assertNull(byX.put(position.x(), submission));
        }
        return byX;
    }

    private record SingleFixture(
        BlockPlaceEvent event,
        BlockState replaced,
        BlockState placed
    ) {
    }

    private static final class RecordingApi implements KansokushaApi {

        private final ConcurrentLinkedQueue<EventSubmission> submissions =
            new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Boolean> outcomes =
            new ConcurrentLinkedQueue<>();
        private final List<Boolean> returnedOutcomes =
            java.util.Collections.synchronizedList(new ArrayList<>());

        private RecordingApi(Boolean... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

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
            var outcome = Optional.ofNullable(this.outcomes.poll()).orElse(true);
            this.returnedOutcomes.add(outcome);
            return outcome;
        }
    }
}
