package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerLecternPageChangeEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.BookMeta;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("removal")
class PaperPlayerItemAuditListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID ITEM_ID =
        UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
    private static final UUID MERCHANT_ID =
        UUID.fromString("323e4567-e89b-12d3-a456-426614174000");

    @Test
    void testDropAndPlayerPickupRecordItemEntity() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world, 1.0, 65.0, 1.0);

        var droppedStack = ItemStack.of(Material.DIAMOND, 3);
        var droppedItem = item(world, ITEM_ID, 10.25, 70.0, -3.75, droppedStack);
        var drop = Mockito.mock(PlayerDropItemEvent.class);
        Mockito.when(drop.getPlayer()).thenReturn(player);
        Mockito.when(drop.getItemDrop()).thenReturn(droppedItem);
        Mockito.when(drop.isCancelled()).thenReturn(false);

        PaperListenerTestSupport.fire(listener, drop);

        var pickupStack = ItemStack.of(Material.EMERALD, 5);
        var pickupItem = item(world, ITEM_ID, 20.75, 71.0, -4.25, pickupStack);
        var pickup = Mockito.mock(EntityPickupItemEvent.class);
        Mockito.when(pickup.getEntity()).thenReturn(player);
        Mockito.when(pickup.getItem()).thenReturn(pickupItem);
        Mockito.when(pickup.getRemaining()).thenReturn(2);
        Mockito.when(pickup.isCancelled()).thenReturn(false);

        PaperListenerTestSupport.fire(listener, pickup);

        Assertions.assertEquals(2, api.submissions.size());
        var dropSubmission = submission(api, PaperPlayerItemAuditListener.ITEM_DROP_EVENT_TYPE);
        Assertions.assertEquals(new PlayerActor(PLAYER_ID), dropSubmission.actor());
        Assertions.assertEquals(Key.key("minecraft", "diamond"), dropSubmission.targetType());
        Assertions.assertEquals(new BlockPosition(10, 70, -4), dropSubmission.position());
        var dropPayload = PaperPayloadNbtCodec.decode(dropSubmission.payload());
        Assertions.assertEquals(ITEM_ID.toString(), string(dropPayload, "item_entity_uuid"));
        Assertions.assertEquals(
            3,
            PaperItemStackPayloadCodec.decode(
                dropPayload.getCompoundOrEmpty("stack")
            ).getAmount()
        );
        Assertions.assertTrue(dropPayload.contains("position"));

        var pickupSubmission = submission(api, PaperPlayerItemAuditListener.ITEM_PICKUP_EVENT_TYPE);
        Assertions.assertEquals(new PlayerActor(PLAYER_ID), pickupSubmission.actor());
        Assertions.assertEquals(Key.key("minecraft", "emerald"), pickupSubmission.targetType());
        Assertions.assertEquals(new BlockPosition(20, 71, -5), pickupSubmission.position());
        var pickupPayload = PaperPayloadNbtCodec.decode(pickupSubmission.payload());
        Assertions.assertEquals(ITEM_ID.toString(), string(pickupPayload, "item_entity_uuid"));
        Assertions.assertEquals(2, pickupPayload.getIntOr("remaining", -1));
        Assertions.assertEquals(
            5,
            PaperItemStackPayloadCodec.decode(
                pickupPayload.getCompoundOrEmpty("stack")
            ).getAmount()
        );
    }

    @Test
    void testNonPlayerPickupIsIgnored() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var mob = Mockito.mock(LivingEntity.class);
        var pickedItem = item(
            world,
            ITEM_ID,
            1,
            2,
            3,
            ItemStack.of(Material.COBBLESTONE, 1)
        );
        var event = Mockito.mock(EntityPickupItemEvent.class);
        Mockito.when(event.getEntity()).thenReturn(mob);
        Mockito.when(event.getItem()).thenReturn(pickedItem);

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
        Mockito.verify(pickedItem, Mockito.never()).getItemStack();
    }

    @Test
    void testBookEditRoundTripsPreviousAndNewBookMeta() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world, 3, 65, 4);

        var previous = bookMeta(
            Material.WRITABLE_BOOK,
            null,
            null,
            Component.text("previous page")
        );
        previous.customName(Component.text("previous custom name"));
        var next = bookMeta(
            Material.WRITTEN_BOOK,
            Component.text("title"),
            Component.text("author"),
            Component.text("new page")
        );
        next.setGeneration(BookMeta.Generation.COPY_OF_ORIGINAL);

        var event = Mockito.mock(PlayerEditBookEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getSlot()).thenReturn(-1);
        Mockito.when(event.getPreviousBookMeta()).thenReturn(previous);
        Mockito.when(event.getNewBookMeta()).thenReturn(next);
        Mockito.when(event.isSigning()).thenReturn(true);
        Mockito.when(event.isCancelled()).thenReturn(false);

        PaperListenerTestSupport.fire(listener, event);
        next.pages(Component.text("mutated new"));

        var payload = PaperPayloadNbtCodec.decode(
            submission(api, PaperPlayerItemAuditListener.BOOK_EDIT_EVENT_TYPE).payload()
        );
        Assertions.assertEquals(-1, payload.getIntOr("slot", Integer.MIN_VALUE));
        Assertions.assertTrue(payload.getBooleanOr("signing", false));

        var restoredPrevious = PaperPlayerItemAuditPayloadCodec.restoreBookMeta(
            payload.getCompoundOrEmpty("previous_book_meta")
        );
        var restoredNew = PaperPlayerItemAuditPayloadCodec.restoreBookMeta(
            payload.getCompoundOrEmpty("new_book_meta")
        );
        Assertions.assertEquals(List.of(Component.text("previous page")), restoredPrevious.pages());
        Assertions.assertEquals(Component.text("previous custom name"), restoredPrevious.customName());
        Assertions.assertEquals(List.of(Component.text("new page")), restoredNew.pages());
        Assertions.assertEquals(Component.text("title"), restoredNew.title());
        Assertions.assertEquals(Component.text("author"), restoredNew.author());
        Assertions.assertEquals(BookMeta.Generation.COPY_OF_ORIGINAL, restoredNew.getGeneration());
    }

    @Test
    void testLecternInsertAndTakeRecordOnlyPersistentBookChanges() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world, 3, 65, 4);
        var block = block(world, 10, 72, -10);

        var insertedBook = ItemStack.of(Material.WRITABLE_BOOK, 1);
        var insert = Mockito.mock(PlayerInsertLecternBookEvent.class);
        Mockito.when(insert.getPlayer()).thenReturn(player);
        Mockito.when(insert.getBlock()).thenReturn(block);
        Mockito.when(insert.getBook()).thenReturn(insertedBook);
        Mockito.when(insert.isCancelled()).thenReturn(false);

        PaperListenerTestSupport.fire(listener, insert);
        insertedBook.setAmount(2);

        var lectern = Mockito.mock(Lectern.class);
        Mockito.when(lectern.getBlock()).thenReturn(block);
        var takenBook = ItemStack.of(Material.WRITTEN_BOOK, 1);
        var take = Mockito.mock(PlayerTakeLecternBookEvent.class);
        Mockito.when(take.getPlayer()).thenReturn(player);
        Mockito.when(take.getLectern()).thenReturn(lectern);
        Mockito.when(take.getBook()).thenReturn(takenBook);
        Mockito.when(take.isCancelled()).thenReturn(false);

        PaperListenerTestSupport.fire(listener, take);

        Assertions.assertEquals(2, api.submissions.size());
        var submissions = api.submissions.stream().toList();
        var insertPayload = PaperPayloadNbtCodec.decode(submissions.get(0).payload());
        Assertions.assertEquals("insert", string(insertPayload, "action"));
        Assertions.assertTrue(
            PaperItemStackPayloadCodec.decode(
                insertPayload.getCompoundOrEmpty("before")
            ).isEmpty()
        );
        Assertions.assertEquals(
            1,
            PaperItemStackPayloadCodec.decode(
                insertPayload.getCompoundOrEmpty("after")
            ).getAmount()
        );

        var takePayload = PaperPayloadNbtCodec.decode(submissions.get(1).payload());
        Assertions.assertEquals("take", string(takePayload, "action"));
        Assertions.assertEquals(
            1,
            PaperItemStackPayloadCodec.decode(
                takePayload.getCompoundOrEmpty("before")
            ).getAmount()
        );
        Assertions.assertTrue(
            PaperItemStackPayloadCodec.decode(
                takePayload.getCompoundOrEmpty("after")
            ).isEmpty()
        );

        var handledTypes = Arrays.stream(PaperPlayerItemAuditListener.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EventHandler.class))
            .flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .toList();
        Assertions.assertFalse(handledTypes.contains(PlayerLecternPageChangeEvent.class));
        Assertions.assertFalse(handledTypes.contains(PlayerInventorySlotChangeEvent.class));
    }

    @Test
    void testTradeAndPurchaseShareOneHandlerAndSubmitOnce() throws Exception {
        Assertions.assertTrue(PlayerPurchaseEvent.class.isAssignableFrom(PlayerTradeEvent.class));
        Assertions.assertSame(
            PlayerPurchaseEvent.getHandlerList(),
            PlayerTradeEvent.getHandlerList()
        );

        var declaredHandlers = Arrays.stream(PaperPlayerItemAuditListener.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EventHandler.class))
            .filter(method -> method.getParameterCount() == 1)
            .toList();
        Assertions.assertEquals(
            1,
            declaredHandlers.stream()
                .map(Method::getParameterTypes)
                .filter(parameters -> parameters[0] == PlayerPurchaseEvent.class)
                .count()
        );
        Assertions.assertEquals(
            0,
            declaredHandlers.stream()
                .map(Method::getParameterTypes)
                .filter(parameters -> parameters[0] == PlayerTradeEvent.class)
                .count()
        );

        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world, 3, 65, 4);
        var villager = Mockito.mock(AbstractVillager.class);
        Mockito.when(villager.getUniqueId()).thenReturn(MERCHANT_ID);
        Mockito.when(villager.getType()).thenReturn(EntityType.VILLAGER);
        Mockito.when(villager.getLocation()).thenReturn(new Location(world, 12.5, 64, -8.5));

        var result = ItemStack.of(Material.DIAMOND, 1);
        var ingredient = ItemStack.of(Material.EMERALD, 7);
        var recipe = new MerchantRecipe(result, 3, 12, true, 5, 0.05F, 2, -1, false);
        recipe.setIngredients(List.of(ingredient));

        var trade = Mockito.mock(PlayerTradeEvent.class);
        Mockito.when(trade.getPlayer()).thenReturn(player);
        Mockito.when(trade.getMerchant()).thenReturn(villager);
        Mockito.when(trade.getTrade()).thenReturn(recipe);
        Mockito.when(trade.isRewardingExp()).thenReturn(true);
        Mockito.when(trade.willIncreaseTradeUses()).thenReturn(true);
        Mockito.when(trade.isCancelled()).thenReturn(false);

        PaperListenerTestSupport.fire(listener, trade);
        result.setAmount(4);
        ingredient.setAmount(1);
        recipe.setUses(11);

        Assertions.assertEquals(1, api.submissions.size());
        var submission = api.submissions.element();
        Assertions.assertEquals(PaperPlayerItemAuditListener.PLAYER_TRADE_EVENT_TYPE, submission.eventType());
        Assertions.assertEquals(new PlayerActor(PLAYER_ID), submission.actor());
        Assertions.assertEquals(Key.key("minecraft", "diamond"), submission.targetType());
        Assertions.assertEquals(new BlockPosition(12, 64, -9), submission.position());

        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(
            "io.papermc.paper.event.player.PlayerTradeEvent",
            string(payload, "source_event")
        );
        var merchant = payload.getCompoundOrEmpty("merchant");
        Assertions.assertEquals("entity", string(merchant, "kind"));
        Assertions.assertEquals(MERCHANT_ID.toString(), string(merchant, "uuid"));
        Assertions.assertEquals("VILLAGER", string(merchant, "type"));

        var tradePayload = payload.getCompoundOrEmpty("trade");
        Assertions.assertEquals(3, tradePayload.getIntOr("uses", -1));
        Assertions.assertEquals(12, tradePayload.getIntOr("max_uses", -1));
        Assertions.assertEquals(
            Material.DIAMOND,
            PaperItemStackPayloadCodec.decode(
                tradePayload.getCompoundOrEmpty("result")
            ).getType()
        );
        var ingredients = tradePayload.getListOrEmpty("ingredients");
        Assertions.assertEquals(1, ingredients.size());
        Assertions.assertEquals(
            7,
            PaperItemStackPayloadCodec.decode((CompoundTag) ingredients.getFirst()).getAmount()
        );
    }

    @Test
    void testStandalonePurchaseRecordsMerchantKind() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world, 9, 64, 9);
        var merchant = Mockito.mock(org.bukkit.inventory.Merchant.class);
        var recipe = new MerchantRecipe(ItemStack.of(Material.BREAD, 1), 5);
        recipe.setIngredients(List.of(ItemStack.of(Material.EMERALD, 1)));

        var purchase = Mockito.mock(PlayerPurchaseEvent.class);
        Mockito.when(purchase.getPlayer()).thenReturn(player);
        Mockito.when(purchase.getMerchant()).thenReturn(merchant);
        Mockito.when(purchase.getTrade()).thenReturn(recipe);
        Mockito.when(purchase.isCancelled()).thenReturn(false);

        PaperListenerTestSupport.fire(listener, purchase);

        var payload = PaperPayloadNbtCodec.decode(api.submissions.element().payload());
        Assertions.assertEquals(
            "io.papermc.paper.event.player.PlayerPurchaseEvent",
            string(payload, "source_event")
        );
        Assertions.assertEquals(
            "standalone",
            string(payload.getCompoundOrEmpty("merchant"), "kind")
        );
    }

    @Test
    void testCancelledEventsDoNotSubmit() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world, 1, 65, 1);

        var droppedItem = item(
            world,
            ITEM_ID,
            1,
            65,
            1,
            ItemStack.of(Material.STONE, 1)
        );
        var drop = Mockito.mock(PlayerDropItemEvent.class);
        Mockito.when(drop.getPlayer()).thenReturn(player);
        Mockito.when(drop.getItemDrop()).thenReturn(droppedItem);
        Mockito.when(drop.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, drop);

        var pickedItem = item(
            world,
            ITEM_ID,
            2,
            65,
            2,
            ItemStack.of(Material.STONE, 1)
        );
        var pickup = Mockito.mock(EntityPickupItemEvent.class);
        Mockito.when(pickup.getEntity()).thenReturn(player);
        Mockito.when(pickup.getItem()).thenReturn(pickedItem);
        Mockito.when(pickup.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, pickup);

        var previous = bookMeta(
            Material.WRITABLE_BOOK,
            null,
            null,
            Component.text("before")
        );
        var next = bookMeta(
            Material.WRITABLE_BOOK,
            null,
            null,
            Component.text("after")
        );
        var edit = Mockito.mock(PlayerEditBookEvent.class);
        Mockito.when(edit.getPlayer()).thenReturn(player);
        Mockito.when(edit.getPreviousBookMeta()).thenReturn(previous);
        Mockito.when(edit.getNewBookMeta()).thenReturn(next);
        Mockito.when(edit.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, edit);

        var block = block(world, 3, 65, 3);
        var insert = Mockito.mock(PlayerInsertLecternBookEvent.class);
        Mockito.when(insert.getPlayer()).thenReturn(player);
        Mockito.when(insert.getBlock()).thenReturn(block);
        Mockito.when(insert.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, insert);

        var lectern = Mockito.mock(Lectern.class);
        Mockito.when(lectern.getBlock()).thenReturn(block);
        var take = Mockito.mock(PlayerTakeLecternBookEvent.class);
        Mockito.when(take.getPlayer()).thenReturn(player);
        Mockito.when(take.getLectern()).thenReturn(lectern);
        Mockito.when(take.getBook()).thenReturn(ItemStack.of(Material.WRITABLE_BOOK, 1));
        Mockito.when(take.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, take);

        var merchant = Mockito.mock(org.bukkit.inventory.Merchant.class);
        var purchase = Mockito.mock(PlayerPurchaseEvent.class);
        Mockito.when(purchase.getPlayer()).thenReturn(player);
        Mockito.when(purchase.getMerchant()).thenReturn(merchant);
        Mockito.when(purchase.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, purchase);

        Assertions.assertTrue(api.submissions.isEmpty());
        Mockito.verify(insert, Mockito.never()).getBook();
        Mockito.verify(purchase, Mockito.never()).getTrade();
    }

    private static PaperPlayerItemAuditListener listener(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        return PaperPlayerItemAuditListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static Player player(World world, double x, double y, double z) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(player.getLocation()).thenReturn(new Location(world, x, y, z));
        return player;
    }

    private static Item item(
        World world,
        UUID uniqueId,
        double x,
        double y,
        double z,
        ItemStack stack
    ) {
        var item = Mockito.mock(Item.class);
        Mockito.when(item.getUniqueId()).thenReturn(uniqueId);
        Mockito.when(item.getLocation()).thenReturn(new Location(world, x, y, z));
        Mockito.when(item.getItemStack()).thenReturn(stack);
        return item;
    }

    private static Block block(World world, int x, int y, int z) {
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(y);
        Mockito.when(block.getZ()).thenReturn(z);
        return block;
    }

    private static BookMeta bookMeta(
        Material material,
        Component title,
        Component author,
        Component page
    ) {
        var item = ItemStack.of(material, 1);
        var meta = Assertions.assertInstanceOf(BookMeta.class, item.getItemMeta());
        meta.pages(page);
        if (title != null) {
            meta.title(title);
        }
        if (author != null) {
            meta.author(author);
        }
        return meta;
    }

    private static EventSubmission submission(
        PaperBlockEventTestSupport.RecordingApi api,
        net.kyori.adventure.key.Key eventType
    ) {
        return api.submissions.stream()
            .filter(submission -> submission.eventType().equals(eventType))
            .findFirst()
            .orElseThrow();
    }

    private static String string(CompoundTag tag, String key) {
        return tag.getString(key).orElseThrow();
    }
}
