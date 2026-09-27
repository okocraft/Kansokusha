package net.okocraft.kansokusha.paper.builtin;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.position.BlockPosition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

class PaperAuditGapListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-28T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174501");
    private static final UUID ENTITY_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174502");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testConfirmedContainerDeltaUsesNetItemChange() {
        var before = new ItemStack[] {
            ItemStack.of(Material.DIAMOND, 3),
            ItemStack.of(Material.EMERALD, 2)
        };
        var after = new ItemStack[] {
            ItemStack.of(Material.DIAMOND, 1),
            ItemStack.of(Material.GOLD_INGOT, 4)
        };

        var deltas = PaperPlayerContainerTransactionListener.diffContents(before, after);

        Assertions.assertEquals(3, deltas.size());
        Assertions.assertEquals(Material.DIAMOND, deltas.get(0).item().getType());
        Assertions.assertEquals(-2, deltas.get(0).amountDelta());
        Assertions.assertEquals(Material.EMERALD, deltas.get(1).item().getType());
        Assertions.assertEquals(-2, deltas.get(1).amountDelta());
        Assertions.assertEquals(Material.GOLD_INGOT, deltas.get(2).item().getType());
        Assertions.assertEquals(4, deltas.get(2).amountDelta());
        Assertions.assertTrue(deltas.stream().allMatch(delta -> delta.item().getAmount() == 1));
    }

    @Test
    void testConfirmedContainerDeltaIgnoresSlotRearrangement() {
        var before = new ItemStack[] {
            ItemStack.of(Material.DIAMOND, 64),
            ItemStack.empty()
        };
        var after = new ItemStack[] {
            ItemStack.of(Material.DIAMOND, 32),
            ItemStack.of(Material.DIAMOND, 32)
        };

        Assertions.assertTrue(
            PaperPlayerContainerTransactionListener.diffContents(before, after).isEmpty()
        );
    }

    @Test
    void testConfirmedContainerDeltaPayloadUsesSignedAmountAndTransactionId() throws Exception {
        var transactionId = UUID.fromString("123e4567-e89b-12d3-a456-426614174503");
        var container = new PaperContainerPayloadCodec.InventorySnapshot(
            new net.minecraft.nbt.CompoundTag(),
            Key.key("minecraft", "overworld"),
            new BlockPosition(10, 64, -3),
            null
        );

        var payload = PaperPayloadNbtCodec.decode(
            PaperAuditGapPayloadCodec.encodeContainerDelta(
                container,
                transactionId,
                "click",
                "HOTBAR_SWAP",
                "NUMBER_KEY",
                "container",
                7,
                7,
                2,
                "added_to_container",
                ItemStack.of(Material.DIAMOND, 1),
                4
            )
        );

        Assertions.assertEquals(
            transactionId.toString(),
            payload.getString("transaction_id").orElseThrow()
        );
        Assertions.assertEquals(
            "added_to_container",
            payload.getString("transfer_direction").orElseThrow()
        );
        Assertions.assertEquals(4, payload.getIntOr("amount_delta", 0));
        Assertions.assertEquals(2, payload.getIntOr("hotbar_button", -1));
        Assertions.assertEquals(
            Material.DIAMOND,
            PaperItemStackPayloadCodec.decode(payload.getCompoundOrEmpty("item")).getType()
        );
    }

    @Test
    void testAuditedBlockInteractionRecordsDirectPlayerAndBlockOnly() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerBlockInteractionListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var world = PaperBlockEventTestSupport.world();
        var player = player(world, 1, 65, 1);
        var block = PaperBlockEventTestSupport.block(
            world,
            3,
            64,
            4,
            net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(),
            Material.CHEST
        );
        Mockito.when(block.getState()).thenReturn(Mockito.mock(Chest.class));

        var event = Mockito.mock(PlayerInteractEvent.class);
        Mockito.when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        Mockito.when(event.getClickedBlock()).thenReturn(block);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getItem()).thenReturn(null);
        Mockito.when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(event.getBlockFace()).thenReturn(org.bukkit.block.BlockFace.NORTH);
        Mockito.when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        Mockito.when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);

        PaperListenerTestSupport.fire(listener, event);

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperPlayerBlockInteractionListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(new PlayerActor(PLAYER_ID), submission.actor());
        Assertions.assertEquals(Key.key("minecraft", "chest"), submission.targetType());
        Assertions.assertEquals(new BlockPosition(3, 64, 4), submission.position());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("interact", payload.getString("operation").orElseThrow());
    }

    @Test
    void testPlayerCausedEntityDeathIsRecordedButNaturalGenericDeathIsNot() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityLifecycleAuditListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var world = PaperBlockEventTestSupport.world();
        var player = player(world, 1, 65, 1);
        Mockito.when(player.getType()).thenReturn(EntityType.PLAYER);

        var zombie = zombie(world);
        var source = Mockito.mock(DamageSource.class);
        Mockito.when(source.getCausingEntity()).thenReturn(player);
        Mockito.when(source.getDamageType()).thenReturn(DamageType.PLAYER_ATTACK);
        Mockito.when(source.isIndirect()).thenReturn(false);

        var killed = Mockito.mock(EntityDeathEvent.class);
        Mockito.when(killed.getEntity()).thenReturn(zombie);
        Mockito.when(killed.getDamageSource()).thenReturn(source);
        Mockito.when(killed.getDroppedExp()).thenReturn(5);
        Mockito.when(killed.getDrops()).thenReturn(List.of(ItemStack.of(Material.ROTTEN_FLESH, 1)));

        PaperListenerTestSupport.fire(listener, killed);

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperEntityLifecycleAuditListener.DEATH_EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(new PlayerActor(PLAYER_ID), submission.actor());
        Assertions.assertEquals(Key.key("minecraft", "zombie"), submission.targetType());

        var naturalSource = Mockito.mock(DamageSource.class);
        Mockito.when(naturalSource.getDamageType()).thenReturn(DamageType.FALL);
        var naturallyDyingZombie = zombie(world);
        var natural = Mockito.mock(EntityDeathEvent.class);
        Mockito.when(natural.getEntity()).thenReturn(naturallyDyingZombie);
        Mockito.when(natural.getDamageSource()).thenReturn(naturalSource);

        PaperListenerTestSupport.fire(listener, natural);
        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testOperationLikeSpawnIsRecordedWithoutInferredActor() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperEntityLifecycleAuditListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var entity = zombie(PaperBlockEventTestSupport.world());
        var event = Mockito.mock(CreatureSpawnEvent.class);
        Mockito.when(event.getEntity()).thenReturn(entity);
        Mockito.when(event.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.SPAWNER_EGG);

        PaperListenerTestSupport.fire(listener, event);

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperEntityLifecycleAuditListener.SPAWN_EVENT_TYPE, submission.eventType());
        Assertions.assertNull(submission.actor());
        Assertions.assertEquals(Key.key("minecraft", "zombie"), submission.targetType());
    }

    @Test
    void testDispenserRecordsOnlyDirectDispenseOperation() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperDispenserAuditListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var world = PaperBlockEventTestSupport.world();
        var block = PaperBlockEventTestSupport.block(
            world,
            8,
            70,
            9,
            net.minecraft.world.level.block.Blocks.DISPENSER.defaultBlockState(),
            Material.DISPENSER
        );
        var item = ItemStack.of(Material.ARROW, 1);
        var event = Mockito.mock(BlockDispenseEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getItem()).thenReturn(item);
        Mockito.when(event.getVelocity()).thenReturn(new Vector(0, 0.2, 1));

        PaperListenerTestSupport.fire(listener, event);

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperDispenserAuditListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(
            new BlockActor(Key.key("minecraft", "dispenser")),
            submission.actor()
        );
        Assertions.assertEquals(Key.key("minecraft", "arrow"), submission.targetType());
        Assertions.assertEquals(new BlockPosition(8, 70, 9), submission.position());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            1,
            PaperItemStackPayloadCodec.decode(payload.getCompoundOrEmpty("item")).getAmount()
        );
    }

    private static Player player(World world, double x, double y, double z) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getLocation()).thenReturn(new Location(world, x, y, z));
        Mockito.when(player.getWorld()).thenReturn(world);
        return player;
    }

    private static Zombie zombie(World world) {
        var zombie = Mockito.mock(Zombie.class);
        Mockito.when(zombie.getUniqueId()).thenReturn(ENTITY_ID);
        Mockito.when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        Mockito.when(zombie.getWorld()).thenReturn(world);
        Mockito.when(zombie.getLocation()).thenReturn(new Location(world, 12.5, 64, -3.5));
        return zombie;
    }

    private static Clock fixedClock() {
        return Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
    }

    private static net.okocraft.kansokusha.api.event.EventSubmission onlySubmission(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.remove();
    }
}
