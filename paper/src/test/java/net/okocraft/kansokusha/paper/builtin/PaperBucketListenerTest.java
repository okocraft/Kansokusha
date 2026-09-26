package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.EquipmentSlot;
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

class PaperBucketListenerTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testEmptyAndFillRemainDistinctAndFinalItemMutationIsCaptured() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var emptyResult = ItemStack.of(Material.BUCKET, 1);
        var empty = bucketEvent(
            PlayerBucketEmptyEvent.class,
            10,
            Blocks.AIR.defaultBlockState().asBlockData(),
            Material.WATER_BUCKET,
            emptyResult,
            false
        );
        var fill = bucketEvent(
            PlayerBucketFillEvent.class,
            20,
            Blocks.WATER.defaultBlockState().asBlockData(),
            Material.BUCKET,
            ItemStack.of(Material.WATER_BUCKET, 1),
            false
        );

        listener.captureEmpty((PlayerBucketEmptyEvent) empty.event());
        emptyResult.setAmount(2);
        listener.finalizeEmpty((PlayerBucketEmptyEvent) empty.event());
        listener.captureFill((PlayerBucketFillEvent) fill.event());
        listener.finalizeFill((PlayerBucketFillEvent) fill.event());

        var byType = new HashMap<Key, EventSubmission>();
        for (var submission : api.submissions) {
            Assertions.assertNull(byType.put(submission.eventType(), submission));
        }
        Assertions.assertEquals(2, byType.size());

        var emptySubmission = byType.get(PaperBucketListener.EMPTY_EVENT_TYPE);
        Assertions.assertNotNull(emptySubmission);
        var emptyPayload = PaperPayloadNbtCodec.decode(emptySubmission.payload());
        Assertions.assertEquals("empty", string(emptyPayload, "operation"));
        Assertions.assertEquals("minecraft:water_bucket", string(emptyPayload, "bucket"));
        Assertions.assertEquals("hand", string(emptyPayload, "hand"));
        Assertions.assertEquals("up", string(emptyPayload, "face"));
        Assertions.assertEquals(9, emptyPayload.getIntOr("clicked_x", Integer.MIN_VALUE));
        Assertions.assertEquals(
            NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()),
            emptyPayload.getCompoundOrEmpty("pre_state")
        );
        var initialItem = PaperItemStackPayloadCodec.decode(
            emptyPayload.getCompoundOrEmpty("initial_result_item")
        );
        var finalItem = PaperItemStackPayloadCodec.decode(
            emptyPayload.getCompoundOrEmpty("final_result_item")
        );
        Assertions.assertEquals(1, initialItem.getAmount());
        Assertions.assertEquals(2, finalItem.getAmount());

        var fillSubmission = byType.get(PaperBucketListener.FILL_EVENT_TYPE);
        Assertions.assertNotNull(fillSubmission);
        var fillPayload = PaperPayloadNbtCodec.decode(fillSubmission.payload());
        Assertions.assertEquals("fill", string(fillPayload, "operation"));
        Assertions.assertEquals("minecraft:bucket", string(fillPayload, "bucket"));
        Assertions.assertEquals(
            NbtUtils.writeBlockState(Blocks.WATER.defaultBlockState()),
            fillPayload.getCompoundOrEmpty("pre_state")
        );
        Assertions.assertFalse(fillPayload.contains("expected_post_state"));
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testNonBlockFillIsIgnored() {
        var api = new RecordingApi();
        var listener = listener(api);
        var fixture = bucketEvent(
            PlayerBucketFillEvent.class,
            25,
            Blocks.GRASS_BLOCK.defaultBlockState().asBlockData(),
            Material.BUCKET,
            ItemStack.of(Material.MILK_BUCKET, 1),
            false
        );
        var event = (PlayerBucketFillEvent) fixture.event();
        Mockito.when(event.getBlockFace()).thenReturn(BlockFace.SELF);

        listener.captureFill(event);
        listener.finalizeFill(event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
        Mockito.verify(fixture.changedBlock(), Mockito.never()).getBlockData();
    }

    @Test
    void testCancelledBucketEventsDoNotSubmit() {
        var api = new RecordingApi();
        var listener = listener(api);
        var empty = bucketEvent(
            PlayerBucketEmptyEvent.class,
            1,
            Blocks.AIR.defaultBlockState().asBlockData(),
            Material.LAVA_BUCKET,
            ItemStack.of(Material.BUCKET, 1),
            true
        );
        var fill = bucketEvent(
            PlayerBucketFillEvent.class,
            2,
            Blocks.LAVA.defaultBlockState().asBlockData(),
            Material.BUCKET,
            ItemStack.of(Material.LAVA_BUCKET, 1),
            true
        );

        listener.captureEmpty((PlayerBucketEmptyEvent) empty.event());
        listener.finalizeEmpty((PlayerBucketEmptyEvent) empty.event());
        listener.captureFill((PlayerBucketFillEvent) fill.event());
        listener.finalizeFill((PlayerBucketFillEvent) fill.event());

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testLowestBlockStateSnapshotIsDetached() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var fixture = bucketEvent(
            PlayerBucketEmptyEvent.class,
            30,
            Blocks.AIR.defaultBlockState().asBlockData(),
            Material.WATER_BUCKET,
            ItemStack.of(Material.BUCKET, 1),
            false
        );
        Mockito.when(fixture.changedBlock().getBlockData()).thenReturn(
            Blocks.AIR.defaultBlockState().asBlockData(),
            Blocks.STONE.defaultBlockState().asBlockData()
        );

        listener.captureEmpty((PlayerBucketEmptyEvent) fixture.event());
        listener.finalizeEmpty((PlayerBucketEmptyEvent) fixture.event());

        var payload = PaperPayloadNbtCodec.decode(onlySubmission(api).payload());
        Assertions.assertEquals(
            NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()),
            payload.getCompoundOrEmpty("pre_state")
        );
        Mockito.verify(fixture.changedBlock(), Mockito.times(1)).getBlockData();
    }

    @Test
    void testConcurrentFoliaStyleBucketEventsDoNotCrossSnapshots() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var fixtures = new ArrayList<BucketFixture>();
        for (int i = 0; i < 32; i++) {
            var type = (i & 1) == 0 ? PlayerBucketEmptyEvent.class : PlayerBucketFillEvent.class;
            fixtures.add(bucketEvent(
                type,
                1000 + i,
                (i & 1) == 0
                    ? Blocks.AIR.defaultBlockState().asBlockData()
                    : Blocks.WATER.defaultBlockState().asBlockData(),
                (i & 1) == 0 ? Material.WATER_BUCKET : Material.BUCKET,
                ItemStack.of((i & 1) == 0 ? Material.BUCKET : Material.WATER_BUCKET, 1),
                false
            ));
        }

        var executor = Executors.newFixedThreadPool(8);
        try {
            var captures = fixtures.stream().map(f -> executor.submit(() -> capture(listener, f))).toList();
            for (var task : captures) {
                task.get();
            }
            var finalizers = fixtures.stream().map(f -> executor.submit(() -> finish(listener, f))).toList();
            for (var task : finalizers) {
                task.get();
            }
        } finally {
            executor.shutdown();
            Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        var byX = new HashMap<Integer, EventSubmission>();
        for (var submission : api.submissions) {
            Assertions.assertNull(byX.put(submission.position().x(), submission));
        }
        Assertions.assertEquals(fixtures.size(), byX.size());
        for (int i = 0; i < fixtures.size(); i++) {
            var submission = byX.get(1000 + i);
            Assertions.assertNotNull(submission);
            Assertions.assertEquals(
                (i & 1) == 0
                    ? PaperBucketListener.EMPTY_EVENT_TYPE
                    : PaperBucketListener.FILL_EVENT_TYPE,
                submission.eventType()
            );
        }
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    private static PaperBucketListener listener(RecordingApi api) {
        return PaperBucketListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static BucketFixture bucketEvent(
        Class<? extends PlayerBucketEvent> type,
        int x,
        org.bukkit.block.data.BlockData blockData,
        Material bucket,
        ItemStack resultItem,
        boolean cancelled
    ) {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));
        var changed = Mockito.mock(Block.class);
        Mockito.when(changed.getWorld()).thenReturn(world);
        Mockito.when(changed.getX()).thenReturn(x);
        Mockito.when(changed.getY()).thenReturn(70);
        Mockito.when(changed.getZ()).thenReturn(-x);
        Mockito.when(changed.getBlockData()).thenReturn(blockData);
        var clicked = Mockito.mock(Block.class);
        Mockito.when(clicked.getX()).thenReturn(x - 1);
        Mockito.when(clicked.getY()).thenReturn(70);
        Mockito.when(clicked.getZ()).thenReturn(-x);

        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        var event = Mockito.mock(type);
        Mockito.when(event.getBlock()).thenReturn(changed);
        Mockito.when(event.getBlockClicked()).thenReturn(clicked);
        Mockito.when(event.getBlockFace()).thenReturn(BlockFace.UP);
        Mockito.when(event.getBucket()).thenReturn(bucket);
        Mockito.when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(event.getItemStack()).thenReturn(resultItem);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        return new BucketFixture(event, changed);
    }

    private static void capture(PaperBucketListener listener, BucketFixture fixture) {
        if (fixture.event() instanceof PlayerBucketEmptyEvent empty) {
            listener.captureEmpty(empty);
        } else {
            listener.captureFill((PlayerBucketFillEvent) fixture.event());
        }
    }

    private static void finish(PaperBucketListener listener, BucketFixture fixture) {
        if (fixture.event() instanceof PlayerBucketEmptyEvent empty) {
            listener.finalizeEmpty(empty);
        } else {
            listener.finalizeFill((PlayerBucketFillEvent) fixture.event());
        }
    }

    private static String string(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getString(key).orElseThrow();
    }

    private static EventSubmission onlySubmission(RecordingApi api) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.element();
    }

    private record BucketFixture(PlayerBucketEvent event, Block changedBlock) {
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
