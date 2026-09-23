package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
import net.kyori.adventure.key.Key;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class PaperBlockHarvestListenerTest {

    private static final Key SERVER_KEY = Key.key("example", "paper");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testHarvestAndShearNormalizeToOneCanonicalTypeWithDistinctSources() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var harvested = new ArrayList<>(List.of(ItemStack.of(Material.SWEET_BERRIES, 2)));
        var harvest = harvestEvent(10, harvested, false);
        var tool = ItemStack.of(Material.SHEARS, 1);
        var drops = new ArrayList<>(List.of(ItemStack.of(Material.HONEYCOMB, 3)));
        var shear = shearEvent(20, tool, drops, false);

        listener.captureHarvest(harvest.event());
        harvested.getFirst().setAmount(7);
        harvested.add(ItemStack.of(Material.DIAMOND, 1));
        listener.finalizeHarvest(harvest.event());

        listener.captureShear(shear.event());
        tool.setAmount(2);
        drops.getFirst().setAmount(8);
        drops.add(ItemStack.of(Material.EMERALD, 1));
        listener.finalizeShear(shear.event());

        var byX = submissionsByX(api);
        Assertions.assertEquals(2, byX.size());

        var harvestSubmission = byX.get(10);
        Assertions.assertEquals(PaperBlockHarvestListener.EVENT_TYPE, harvestSubmission.eventType());
        var harvestPayload = PaperAdditionalBuiltInPayloadCodec.decode(harvestSubmission.payload());
        Assertions.assertEquals("harvest", string(harvestPayload, "operation"));
        Assertions.assertEquals(
            "org.bukkit.event.player.PlayerHarvestBlockEvent",
            string(harvestPayload, "source_event")
        );
        Assertions.assertEquals("hand", string(harvestPayload, "hand"));
        Assertions.assertEquals(
            NbtUtils.writeBlockState(Blocks.SWEET_BERRY_BUSH.defaultBlockState()),
            PaperAdditionalBuiltInPayloadCodec.decodeNestedBlockState(harvestPayload, "pre_state")
        );
        var harvestItems = PaperAdditionalBuiltInPayloadCodec.decodeNestedItems(
            harvestPayload,
            "harvest_items"
        );
        Assertions.assertEquals(1, harvestItems.size());
        Assertions.assertEquals(2, PaperItemStackPayloadCodec.decode(harvestItems.getFirst()).getAmount());
        Assertions.assertTrue(
            PaperItemStackPayloadCodec.decode(
                PaperAdditionalBuiltInPayloadCodec.decodeNestedItem(harvestPayload, "shear_tool")
            ).isEmpty()
        );

        var shearSubmission = byX.get(20);
        Assertions.assertEquals(PaperBlockHarvestListener.EVENT_TYPE, shearSubmission.eventType());
        var shearPayload = PaperAdditionalBuiltInPayloadCodec.decode(shearSubmission.payload());
        Assertions.assertEquals("shear", string(shearPayload, "operation"));
        Assertions.assertEquals(
            "io.papermc.paper.event.block.PlayerShearBlockEvent",
            string(shearPayload, "source_event")
        );
        var snapshottedTool = PaperItemStackPayloadCodec.decode(
            PaperAdditionalBuiltInPayloadCodec.decodeNestedItem(shearPayload, "shear_tool")
        );
        Assertions.assertEquals(Material.SHEARS, snapshottedTool.getType());
        Assertions.assertEquals(1, snapshottedTool.getAmount());
        var snapshottedDrops = PaperAdditionalBuiltInPayloadCodec.decodeNestedItems(
            shearPayload,
            "shear_drops"
        );
        Assertions.assertEquals(1, snapshottedDrops.size());
        Assertions.assertEquals(
            3,
            PaperItemStackPayloadCodec.decode(snapshottedDrops.getFirst()).getAmount()
        );
        Assertions.assertEquals(0, listener.inFlightCount());
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

        listener.captureHarvest(harvest.event());
        listener.finalizeHarvest(harvest.event());
        listener.captureShear(shear.event());
        listener.finalizeShear(shear.event());

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testDuplicatePreventionAndSourceWiringExcludeBlockBreak() {
        var api = new RecordingApi();
        var listener = listener(api);
        var harvest = harvestEvent(
            5,
            new ArrayList<>(List.of(ItemStack.of(Material.SWEET_BERRIES, 1))),
            false
        );

        listener.captureHarvest(harvest.event());
        listener.finalizeHarvest(harvest.event());
        listener.finalizeHarvest(harvest.event());

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

    @Test
    void testRegistrationConflictFails() {
        var api = Mockito.mock(KansokushaApi.class);
        Mockito.when(api.registerEventType(Mockito.any())).thenReturn(RegistrationOutcome.CONFLICT);

        var failure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> PaperBlockHarvestListener.register(api, SERVER_KEY)
        );

        Assertions.assertTrue(failure.getMessage().contains("kansokusha:block_harvest"));
    }

    @Test
    void testConcurrentFoliaStyleHarvestEventsDoNotCrossSnapshots() throws Exception {
        var api = new RecordingApi();
        var listener = listener(api);
        var fixtures = new ArrayList<Fixture>();
        for (int i = 0; i < 32; i++) {
            if ((i & 1) == 0) {
                fixtures.add(new Fixture(
                    harvestEvent(
                        1000 + i,
                        new ArrayList<>(List.of(ItemStack.of(Material.SWEET_BERRIES, i + 1))),
                        false
                    ),
                    null
                ));
            } else {
                fixtures.add(new Fixture(
                    null,
                    shearEvent(
                        1000 + i,
                        ItemStack.of(Material.SHEARS, 1),
                        new ArrayList<>(List.of(ItemStack.of(Material.HONEYCOMB, i + 1))),
                        false
                    )
                ));
            }
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

        var byX = submissionsByX(api);
        Assertions.assertEquals(fixtures.size(), byX.size());
        for (int i = 0; i < fixtures.size(); i++) {
            var payload = PaperAdditionalBuiltInPayloadCodec.decode(byX.get(1000 + i).payload());
            Assertions.assertEquals((i & 1) == 0 ? "harvest" : "shear", string(payload, "operation"));
        }
        Assertions.assertEquals(0, listener.inFlightCount());
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

    private static void capture(PaperBlockHarvestListener listener, Fixture fixture) {
        if (fixture.harvest() != null) {
            listener.captureHarvest(fixture.harvest().event());
        } else {
            listener.captureShear(fixture.shear().event());
        }
    }

    private static void finish(PaperBlockHarvestListener listener, Fixture fixture) {
        if (fixture.harvest() != null) {
            listener.finalizeHarvest(fixture.harvest().event());
        } else {
            listener.finalizeShear(fixture.shear().event());
        }
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

    private record Fixture(HarvestFixture harvest, ShearFixture shear) {
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
