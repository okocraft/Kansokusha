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
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

class PaperBucketListenerTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testEmptyAndFillSubmitDistinctEventTypes() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var empty = bucketEvent(
            PlayerBucketEmptyEvent.class,
            10,
            Blocks.AIR.defaultBlockState().asBlockData(),
            Material.WATER_BUCKET,
            ItemStack.of(Material.BUCKET, 1),
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

        PaperListenerTestSupport.fire(listener, (PlayerBucketEmptyEvent) empty.event());
        PaperListenerTestSupport.fire(listener, (PlayerBucketFillEvent) fill.event());

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
        var resultItem = PaperItemStackPayloadCodec.decode(
            emptyPayload.getCompoundOrEmpty("result_item")
        );
        Assertions.assertEquals(Material.BUCKET, resultItem.getType());
        Assertions.assertEquals(1, resultItem.getAmount());

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

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
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

        PaperListenerTestSupport.fire(listener, (PlayerBucketEmptyEvent) empty.event());
        PaperListenerTestSupport.fire(listener, (PlayerBucketFillEvent) fill.event());

        Assertions.assertTrue(api.submissions.isEmpty());
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
        return new BucketFixture(event);
    }

    private static String string(net.minecraft.nbt.CompoundTag tag, String key) {
        return tag.getString(key).orElseThrow();
    }

    private record BucketFixture(PlayerBucketEvent event) {
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
