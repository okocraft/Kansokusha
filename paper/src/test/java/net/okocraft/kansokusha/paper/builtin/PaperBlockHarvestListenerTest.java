package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

class PaperBlockHarvestListenerTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testHarvestAndShearNormalizeToOneCanonicalTypeWithDistinctOperations() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var harvested = new ArrayList<>(List.of(ItemStack.of(Material.SWEET_BERRIES, 2)));
        var harvest = harvestEvent(10, harvested, false);
        var tool = ItemStack.of(Material.SHEARS, 1);
        var drops = new ArrayList<>(List.of(ItemStack.of(Material.HONEYCOMB, 3)));
        var shear = shearEvent(20, tool, drops, false);

        PaperListenerTestSupport.fire(listener, harvest.event());
        PaperListenerTestSupport.fire(listener, shear.event());

        var byX = submissionsByX(api);
        Assertions.assertEquals(2, byX.size());

        var harvestSubmission = byX.get(10);
        Assertions.assertEquals(PaperBlockHarvestListener.EVENT_TYPE, harvestSubmission.eventType());
        var harvestPayload = PaperPayloadNbtCodec.decode(harvestSubmission.payload());
        Assertions.assertEquals("harvest", string(harvestPayload, "operation"));
        Assertions.assertEquals("hand", string(harvestPayload, "hand"));
        Assertions.assertEquals(
            NbtUtils.writeBlockState(Blocks.SWEET_BERRY_BUSH.defaultBlockState()),
            harvestPayload.getCompoundOrEmpty("pre_state")
        );
        var harvestItems = harvestPayload.getListOrEmpty("harvest_items").stream()
            .map(CompoundTag.class::cast)
            .toList();
        Assertions.assertEquals(1, harvestItems.size());
        Assertions.assertEquals(2, PaperItemStackPayloadCodec.decode(harvestItems.getFirst()).getAmount());
        Assertions.assertTrue(
            PaperItemStackPayloadCodec.decode(
                harvestPayload.getCompoundOrEmpty("shear_tool")
            ).isEmpty()
        );

        var shearSubmission = byX.get(20);
        Assertions.assertEquals(PaperBlockHarvestListener.EVENT_TYPE, shearSubmission.eventType());
        var shearPayload = PaperPayloadNbtCodec.decode(shearSubmission.payload());
        Assertions.assertEquals("shear", string(shearPayload, "operation"));
        var snapshottedTool = PaperItemStackPayloadCodec.decode(
            shearPayload.getCompoundOrEmpty("shear_tool")
        );
        Assertions.assertEquals(Material.SHEARS, snapshottedTool.getType());
        Assertions.assertEquals(1, snapshottedTool.getAmount());
        var snapshottedDrops = shearPayload.getListOrEmpty("shear_drops").stream()
            .map(CompoundTag.class::cast)
            .toList();
        Assertions.assertEquals(1, snapshottedDrops.size());
        Assertions.assertEquals(
            3,
            PaperItemStackPayloadCodec.decode(snapshottedDrops.getFirst()).getAmount()
        );
    }

    @Test
    void testCancelledHarvestAndShearDoNotSubmit() {
        var api = new RecordingApi();
        var listener = listener(api);
        var harvest = harvestEvent(
            1,
            new ArrayList<>(List.of(ItemStack.of(Material.SWEET_BERRIES, 1))),
            true
        );
        var shear = shearEvent(
            2,
            ItemStack.of(Material.SHEARS, 1),
            new ArrayList<>(List.of(ItemStack.of(Material.HONEYCOMB, 1))),
            true
        );

        PaperListenerTestSupport.fire(listener, harvest.event());
        PaperListenerTestSupport.fire(listener, shear.event());

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testSourceWiringExcludesBlockBreak() {
        var api = new RecordingApi();
        var listener = listener(api);
        var harvest = harvestEvent(
            5,
            new ArrayList<>(List.of(ItemStack.of(Material.SWEET_BERRIES, 1))),
            false
        );

        PaperListenerTestSupport.fire(listener, harvest.event());

        Assertions.assertEquals(1, api.submissions.size());
        var handledEventTypes = Arrays.stream(PaperBlockHarvestListener.class.getDeclaredMethods())
            .filter(method -> method.getAnnotation(EventHandler.class) != null)
            .flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .toList();
        Assertions.assertFalse(handledEventTypes.contains(BlockBreakEvent.class));
        Assertions.assertTrue(handledEventTypes.contains(PlayerHarvestBlockEvent.class));
        Assertions.assertTrue(handledEventTypes.contains(PlayerShearBlockEvent.class));
        Assertions.assertFalse(PlayerHarvestBlockEvent.class.isAssignableFrom(PlayerShearBlockEvent.class));
        Assertions.assertFalse(PlayerShearBlockEvent.class.isAssignableFrom(PlayerHarvestBlockEvent.class));
    }

    private static PaperBlockHarvestListener listener(RecordingApi api) {
        return PaperBlockHarvestListener.register(
            api,
            SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static HarvestFixture harvestEvent(
        int x,
        ArrayList<ItemStack> items,
        boolean cancelled
    ) {
        var block = block(x, Blocks.SWEET_BERRY_BUSH.defaultBlockState().asBlockData());
        var player = player();
        var event = Mockito.mock(PlayerHarvestBlockEvent.class);
        Mockito.when(event.getHarvestedBlock()).thenReturn(block);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(event.getItemsHarvested()).thenReturn(items);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        return new HarvestFixture(event);
    }

    private static ShearFixture shearEvent(
        int x,
        ItemStack tool,
        ArrayList<ItemStack> drops,
        boolean cancelled
    ) {
        var block = block(x, Blocks.BEEHIVE.defaultBlockState().asBlockData());
        var player = player();
        var event = Mockito.mock(PlayerShearBlockEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getItem()).thenReturn(tool);
        Mockito.when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(event.getDrops()).thenReturn(drops);
        Mockito.when(event.isCancelled()).thenReturn(cancelled);
        return new ShearFixture(event);
    }

    private static Block block(int x, org.bukkit.block.data.BlockData blockData) {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(75);
        Mockito.when(block.getZ()).thenReturn(-x);
        Mockito.when(block.getBlockData()).thenReturn(blockData);
        return block;
    }

    private static Player player() {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        return player;
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

    private record HarvestFixture(PlayerHarvestBlockEvent event) {
    }

    private record ShearFixture(PlayerShearBlockEvent event) {
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
