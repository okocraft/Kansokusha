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
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
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
    void testPlayerContainerClickRecordsLocatedContainerOperation() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = PaperPlayerContainerTransactionListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            fixedClock()
        );
        var world = PaperBlockEventTestSupport.world();
        var player = player(world, 5, 65, 5);

        var top = Mockito.mock(Inventory.class);
        Mockito.when(top.getType()).thenReturn(InventoryType.CHEST);
        Mockito.when(top.getSize()).thenReturn(27);
        Mockito.when(top.getHolder()).thenReturn(null);
        Mockito.when(top.getLocation()).thenReturn(new Location(world, 10.25, 64, -2.75));

        var view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);

        var event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getClickedInventory()).thenReturn(top);
        Mockito.when(event.getAction()).thenReturn(InventoryAction.PICKUP_ALL);
        Mockito.when(event.getClick()).thenReturn(org.bukkit.event.inventory.ClickType.LEFT);
        Mockito.when(event.getCurrentItem()).thenReturn(ItemStack.of(Material.DIAMOND, 3));
        Mockito.when(event.getCursor()).thenReturn(ItemStack.empty());
        Mockito.when(event.getSlot()).thenReturn(4);
        Mockito.when(event.getRawSlot()).thenReturn(4);

        PaperListenerTestSupport.fire(listener, event);

        var submission = onlySubmission(api);
        Assertions.assertEquals(PaperPlayerContainerTransactionListener.EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(new PlayerActor(PLAYER_ID), submission.actor());
        Assertions.assertEquals(Key.key("minecraft", "diamond"), submission.targetType());
        Assertions.assertEquals(new BlockPosition(10, 64, -3), submission.position());

        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            "container_to_player",
            payload.getString("transfer_direction").orElseThrow()
        );
        Assertions.assertEquals("container", payload.getString("clicked_scope").orElseThrow());
        Assertions.assertEquals(4, payload.getIntOr("raw_slot", -1));
        Assertions.assertEquals(
            3,
            PaperItemStackPayloadCodec.decode(payload.getCompoundOrEmpty("item_before")).getAmount()
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
        var natural = Mockito.mock(EntityDeathEvent.class);
        Mockito.when(natural.getEntity()).thenReturn(zombie(world));
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
