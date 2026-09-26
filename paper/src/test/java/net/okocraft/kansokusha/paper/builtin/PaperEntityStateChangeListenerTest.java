package net.okocraft.kansokusha.paper.builtin;

import io.papermc.paper.entity.Leashable;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class PaperEntityStateChangeListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");
    private static final UUID TARGET_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174120");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174121");
    private static final UUID HOLDER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174122");

    @Test
    void testArmorStandManipulateRecordsTargetSlotAndBeforeItems() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var armorStand = stubEntity(
            Mockito.mock(ArmorStand.class),
            world,
            EntityType.ARMOR_STAND,
            TARGET_ID,
            12.75,
            64,
            -6.25
        );
        var player = player(world);
        var playerItem = ItemStack.of(Material.DIAMOND_HELMET, 1);
        var armorStandItem = ItemStack.of(Material.CARVED_PUMPKIN, 1);
        var event = Mockito.mock(PlayerArmorStandManipulateEvent.class);
        Mockito.when(event.getRightClicked()).thenReturn(armorStand);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getPlayerItem()).thenReturn(playerItem);
        Mockito.when(event.getArmorStandItem()).thenReturn(armorStandItem);
        Mockito.when(event.getSlot()).thenReturn(EquipmentSlot.HEAD);
        Mockito.when(event.getHand()).thenReturn(EquipmentSlot.HAND);

        PaperListenerTestSupport.fire(listener, event);

        var submission = onlySubmission(api);
        assertCommon(
            submission,
            PaperEntityStateChangeListener.ARMOR_STAND_MANIPULATE_EVENT_TYPE,
            new BlockPosition(12, 64, -7)
        );
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());

        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        assertEntity(payload.getCompoundOrEmpty("target"), TARGET_ID, "minecraft:armor_stand");
        Assertions.assertEquals(
            "head",
            payload.getString("equipment_slot").orElseThrow()
        );
        Assertions.assertEquals("hand", payload.getString("hand").orElseThrow());
        Assertions.assertEquals(
            1,
            PaperItemStackPayloadCodec.decode(
                payload.getCompoundOrEmpty("player_item_before")
            ).getAmount()
        );
        Assertions.assertEquals(
            Material.CARVED_PUMPKIN,
            PaperItemStackPayloadCodec.decode(
                payload.getCompoundOrEmpty("armor_stand_item_before")
            ).getType()
        );
    }

    @Test
    void testArmorStandEmptyInteractionIsNotRecorded() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var event = Mockito.mock(PlayerArmorStandManipulateEvent.class);
        Mockito.when(event.getPlayerItem()).thenReturn(ItemStack.empty());
        Mockito.when(event.getArmorStandItem()).thenReturn(ItemStack.empty());

        PaperListenerTestSupport.fire(listener, event);

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    @Test
    void testLeashAndUnleashRecordTargetHolderActionAndReason() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world);
        var holder = stubEntity(
            Mockito.mock(Entity.class),
            world,
            EntityType.ARMOR_STAND,
            HOLDER_ID,
            5,
            65,
            5
        );
        var leashTarget = stubEntity(
            Mockito.mock(Entity.class),
            world,
            EntityType.COW,
            TARGET_ID,
            6.5,
            65,
            5.5
        );
        var leash = Mockito.mock(PlayerLeashEntityEvent.class);
        Mockito.when(leash.getEntity()).thenReturn(leashTarget);
        Mockito.when(leash.getLeashHolder()).thenReturn(holder);
        Mockito.when(leash.getPlayer()).thenReturn(player);
        Mockito.when(leash.getHand()).thenReturn(EquipmentSlot.OFF_HAND);

        PaperListenerTestSupport.fire(listener, leash);

        var leashSubmission = onlySubmission(api);
        assertCommon(
            leashSubmission,
            PaperEntityStateChangeListener.ENTITY_LEASH_CHANGE_EVENT_TYPE,
            new BlockPosition(6, 65, 5)
        );
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), leashSubmission.subject());
        var leashPayload = PaperPayloadNbtCodec.decode(leashSubmission.payload());
        Assertions.assertEquals("leash", leashPayload.getString("action").orElseThrow());
        Assertions.assertEquals(
            "player_leash",
            leashPayload.getString("reason").orElseThrow()
        );
        Assertions.assertEquals("off_hand", leashPayload.getString("hand").orElseThrow());
        Assertions.assertFalse(leashPayload.getBoolean("drop_leash").orElseThrow());
        assertEntity(
            leashPayload.getCompoundOrEmpty("target"),
            TARGET_ID,
            "minecraft:cow"
        );
        assertEntity(
            leashPayload.getCompoundOrEmpty("holder"),
            HOLDER_ID,
            "minecraft:armor_stand"
        );

        Mockito.when(holder.getType()).thenReturn(EntityType.ARMOR_STAND);
        var unleashTarget = stubEntity(
            Mockito.mock(Leashable.class),
            world,
            EntityType.COW,
            TARGET_ID,
            -2.25,
            70,
            3.75
        );
        Mockito.when(unleashTarget.isLeashed()).thenReturn(true);
        Mockito.when(unleashTarget.getLeashHolder()).thenReturn(holder);
        var unleash = Mockito.mock(PlayerUnleashEntityEvent.class);
        Mockito.when(unleash.getEntity()).thenReturn(unleashTarget);
        Mockito.when(unleash.getPlayer()).thenReturn(player);
        Mockito.when(unleash.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(unleash.getReason())
            .thenReturn(EntityUnleashEvent.UnleashReason.PLAYER_UNLEASH);
        Mockito.when(unleash.isDropLeash()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, unleash);

        var unleashSubmission = onlySubmission(api);
        assertCommon(
            unleashSubmission,
            PaperEntityStateChangeListener.ENTITY_LEASH_CHANGE_EVENT_TYPE,
            new BlockPosition(-3, 70, 3)
        );
        var unleashPayload = PaperPayloadNbtCodec.decode(unleashSubmission.payload());
        Assertions.assertEquals(
            "unleash",
            unleashPayload.getString("action").orElseThrow()
        );
        Assertions.assertEquals(
            "player_unleash",
            unleashPayload.getString("reason").orElseThrow()
        );
        Assertions.assertTrue(unleashPayload.getBoolean("drop_leash").orElseThrow());
        assertEntity(
            unleashPayload.getCompoundOrEmpty("holder"),
            HOLDER_ID,
            "minecraft:armor_stand"
        );
    }

    @Test
    void testItemFrameChangeRecordsBeforeStateAndNormalizedItem() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world);
        var frame = stubEntity(
            Mockito.mock(ItemFrame.class),
            world,
            EntityType.ITEM_FRAME,
            TARGET_ID,
            20.5,
            70,
            30.5
        );
        var framedItem = ItemStack.of(Material.MAP, 1);
        Mockito.when(frame.getItem()).thenReturn(framedItem);
        Mockito.when(frame.getRotation()).thenReturn(Rotation.CLOCKWISE_45);
        Mockito.when(frame.isFixed()).thenReturn(true);
        var event = Mockito.mock(PlayerItemFrameChangeEvent.class);
        Mockito.when(event.getItemFrame()).thenReturn(frame);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getAction())
            .thenReturn(PlayerItemFrameChangeEvent.ItemFrameChangeAction.ROTATE);
        Mockito.when(event.getItemStack()).thenReturn(ItemStack.of(Material.DIAMOND, 64));

        PaperListenerTestSupport.fire(listener, event);

        var submission = onlySubmission(api);
        assertCommon(
            submission,
            PaperEntityStateChangeListener.ITEM_FRAME_CHANGE_EVENT_TYPE,
            new BlockPosition(20, 70, 30)
        );
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals("rotate", payload.getString("action").orElseThrow());
        Assertions.assertEquals(
            Material.MAP,
            PaperItemStackPayloadCodec.decode(
                payload.getCompoundOrEmpty("item_before")
            ).getType()
        );
        var itemAfter = PaperItemStackPayloadCodec.decode(
            payload.getCompoundOrEmpty("item_after")
        );
        Assertions.assertEquals(Material.DIAMOND, itemAfter.getType());
        Assertions.assertEquals(1, itemAfter.getAmount());
        Assertions.assertEquals(
            "clockwise_45",
            payload.getString("rotation_before").orElseThrow()
        );
        Assertions.assertEquals(
            "clockwise",
            payload.getString("rotation_after").orElseThrow()
        );
        Assertions.assertTrue(payload.getBoolean("fixed_before").orElseThrow());
        Assertions.assertTrue(payload.getBoolean("fixed_after").orElseThrow());
        assertEntity(
            payload.getCompoundOrEmpty("target"),
            TARGET_ID,
            "minecraft:item_frame"
        );
    }

    @Test
    void testItemFramePlaceAndRemoveDescribeContentsNotFramePlacementOrBreak() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world);
        var frame = stubEntity(
            Mockito.mock(ItemFrame.class),
            world,
            EntityType.ITEM_FRAME,
            TARGET_ID,
            1,
            2,
            3
        );
        Mockito.when(frame.getRotation()).thenReturn(Rotation.NONE);
        Mockito.when(frame.isFixed()).thenReturn(false);

        Mockito.when(frame.getItem()).thenReturn(ItemStack.empty());
        var placed = ItemStack.of(Material.DIAMOND, 64);
        var place = Mockito.mock(PlayerItemFrameChangeEvent.class);
        Mockito.when(place.getItemFrame()).thenReturn(frame);
        Mockito.when(place.getPlayer()).thenReturn(player);
        Mockito.when(place.getAction())
            .thenReturn(PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE);
        Mockito.when(place.getItemStack()).thenReturn(placed);
        PaperListenerTestSupport.fire(listener, place);

        var placePayload = PaperPayloadNbtCodec.decode(onlySubmission(api).payload());
        Assertions.assertEquals("place", placePayload.getString("action").orElseThrow());
        Assertions.assertTrue(
            PaperItemStackPayloadCodec.decode(
                placePayload.getCompoundOrEmpty("item_before")
            ).isEmpty()
        );
        var placedAfter = PaperItemStackPayloadCodec.decode(
            placePayload.getCompoundOrEmpty("item_after")
        );
        Assertions.assertEquals(Material.DIAMOND, placedAfter.getType());
        Assertions.assertEquals(1, placedAfter.getAmount());

        var framedDiamond = ItemStack.of(Material.DIAMOND, 1);
        Mockito.when(frame.getItem()).thenReturn(framedDiamond);
        var remove = Mockito.mock(PlayerItemFrameChangeEvent.class);
        Mockito.when(remove.getItemFrame()).thenReturn(frame);
        Mockito.when(remove.getPlayer()).thenReturn(player);
        Mockito.when(remove.getAction())
            .thenReturn(PlayerItemFrameChangeEvent.ItemFrameChangeAction.REMOVE);
        Mockito.when(remove.getItemStack()).thenReturn(placed);
        PaperListenerTestSupport.fire(listener, remove);

        var removePayload = PaperPayloadNbtCodec.decode(onlySubmission(api).payload());
        Assertions.assertEquals(
            "remove",
            removePayload.getString("action").orElseThrow()
        );
        Assertions.assertEquals(
            Material.DIAMOND,
            PaperItemStackPayloadCodec.decode(
                removePayload.getCompoundOrEmpty("item_before")
            ).getType()
        );
        Assertions.assertTrue(
            PaperItemStackPayloadCodec.decode(
                removePayload.getCompoundOrEmpty("item_after")
            ).isEmpty()
        );
    }

    @Test
    void testTameRecordsTargetAndNewOwner() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var target = stubEntity(
            Mockito.mock(LivingEntity.class),
            world,
            EntityType.WOLF,
            TARGET_ID,
            -8.5,
            63,
            10.25
        );
        var owner = player(world);
        var event = Mockito.mock(EntityTameEvent.class);
        Mockito.when(event.getEntity()).thenReturn(target);
        Mockito.when(event.getOwner()).thenReturn(owner);

        PaperListenerTestSupport.fire(listener, event);

        var submission = onlySubmission(api);
        assertCommon(
            submission,
            PaperEntityStateChangeListener.ENTITY_TAME_EVENT_TYPE,
            new BlockPosition(-9, 63, 10)
        );
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        assertEntity(payload.getCompoundOrEmpty("target"), TARGET_ID, "minecraft:wolf");
        assertEntity(
            payload.getCompoundOrEmpty("new_owner"),
            PLAYER_ID,
            "minecraft:player"
        );
    }

    @Test
    void testNameChangeRecordsPreviousAndNewNameAndPersistentState() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world);
        var target = stubEntity(
            Mockito.mock(LivingEntity.class),
            world,
            EntityType.VILLAGER,
            HOLDER_ID,
            9.5,
            81,
            -2.5
        );
        Mockito.when(target.customName()).thenReturn(Component.text("before"));
        var event = Mockito.mock(PlayerNameEntityEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getEntity()).thenReturn(target);
        Mockito.when(event.getName()).thenReturn(Component.text("after"));
        Mockito.when(event.isPersistent()).thenReturn(true);

        PaperListenerTestSupport.fire(listener, event);

        var submission = onlySubmission(api);
        assertCommon(
            submission,
            PaperEntityStateChangeListener.ENTITY_NAME_CHANGE_EVENT_TYPE,
            new BlockPosition(9, 81, -3)
        );
        Assertions.assertEquals(new PlayerSubject(PLAYER_ID), submission.subject());
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        assertEntity(
            payload.getCompoundOrEmpty("target"),
            HOLDER_ID,
            "minecraft:villager"
        );
        Assertions.assertEquals(
            Component.text("before"),
            decodeComponent(payload.getCompoundOrEmpty("previous_custom_name"))
        );
        Assertions.assertEquals(
            Component.text("after"),
            decodeComponent(payload.getCompoundOrEmpty("new_custom_name"))
        );
        Assertions.assertTrue(payload.getBoolean("persistent").orElseThrow());
    }

    @Test
    void testNullEntityNamesAreRepresentedAsEmptyComponentValues() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var target = stubEntity(
            Mockito.mock(LivingEntity.class),
            world,
            EntityType.SHEEP,
            TARGET_ID,
            0,
            64,
            0
        );
        var event = Mockito.mock(PlayerNameEntityEvent.class);
        var namingPlayer = player(world);
        Mockito.when(event.getEntity()).thenReturn(target);
        Mockito.when(event.getPlayer()).thenReturn(namingPlayer);
        Mockito.when(event.getName()).thenReturn(null);

        PaperListenerTestSupport.fire(listener, event);

        var payload = PaperPayloadNbtCodec.decode(onlySubmission(api).payload());
        Assertions.assertTrue(payload.getCompoundOrEmpty("previous_custom_name").isEmpty());
        Assertions.assertTrue(payload.getCompoundOrEmpty("new_custom_name").isEmpty());
    }

    @Test
    void testCancelledChangesAreDroppedForEveryCancellableEvent() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();
        var player = player(world);
        var holder = stubEntity(
            Mockito.mock(Entity.class),
            world,
            EntityType.ARMOR_STAND,
            HOLDER_ID,
            1,
            2,
            3
        );

        var armorStand = stubEntity(
            Mockito.mock(ArmorStand.class),
            world,
            EntityType.ARMOR_STAND,
            TARGET_ID,
            1,
            2,
            3
        );
        var armor = Mockito.mock(PlayerArmorStandManipulateEvent.class);
        Mockito.when(armor.getRightClicked()).thenReturn(armorStand);
        Mockito.when(armor.getPlayer()).thenReturn(player);
        Mockito.when(armor.getPlayerItem()).thenReturn(ItemStack.of(Material.STICK, 1));
        Mockito.when(armor.getArmorStandItem()).thenReturn(ItemStack.empty());
        Mockito.when(armor.getSlot()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(armor.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(armor.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, armor);

        var leashTarget = stubEntity(
            Mockito.mock(Entity.class),
            world,
            EntityType.COW,
            TARGET_ID,
            1,
            2,
            3
        );
        var leash = Mockito.mock(PlayerLeashEntityEvent.class);
        Mockito.when(leash.getEntity()).thenReturn(leashTarget);
        Mockito.when(leash.getLeashHolder()).thenReturn(holder);
        Mockito.when(leash.getPlayer()).thenReturn(player);
        Mockito.when(leash.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(leash.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, leash);

        var unleashTarget = stubEntity(
            Mockito.mock(Leashable.class),
            world,
            EntityType.COW,
            TARGET_ID,
            1,
            2,
            3
        );
        Mockito.when(unleashTarget.isLeashed()).thenReturn(true);
        Mockito.when(unleashTarget.getLeashHolder()).thenReturn(holder);
        var unleash = Mockito.mock(PlayerUnleashEntityEvent.class);
        Mockito.when(unleash.getEntity()).thenReturn(unleashTarget);
        Mockito.when(unleash.getPlayer()).thenReturn(player);
        Mockito.when(unleash.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(unleash.getReason())
            .thenReturn(EntityUnleashEvent.UnleashReason.PLAYER_UNLEASH);
        Mockito.when(unleash.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, unleash);
        Mockito.verify(unleash, Mockito.never()).isDropLeash();

        var frame = stubEntity(
            Mockito.mock(ItemFrame.class),
            world,
            EntityType.ITEM_FRAME,
            TARGET_ID,
            1,
            2,
            3
        );
        Mockito.when(frame.getItem()).thenReturn(ItemStack.empty());
        Mockito.when(frame.getRotation()).thenReturn(Rotation.NONE);
        var frameEvent = Mockito.mock(PlayerItemFrameChangeEvent.class);
        Mockito.when(frameEvent.getItemFrame()).thenReturn(frame);
        Mockito.when(frameEvent.getPlayer()).thenReturn(player);
        Mockito.when(frameEvent.getAction())
            .thenReturn(PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE);
        Mockito.when(frameEvent.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, frameEvent);
        Mockito.verify(frameEvent, Mockito.never()).getItemStack();

        var tameTarget = stubEntity(
            Mockito.mock(LivingEntity.class),
            world,
            EntityType.WOLF,
            TARGET_ID,
            1,
            2,
            3
        );
        var tame = Mockito.mock(EntityTameEvent.class);
        Mockito.when(tame.getEntity()).thenReturn(tameTarget);
        Mockito.when(tame.getOwner()).thenReturn(player);
        Mockito.when(tame.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, tame);

        var name = Mockito.mock(PlayerNameEntityEvent.class);
        Mockito.when(name.getPlayer()).thenReturn(player);
        Mockito.when(name.isCancelled()).thenReturn(true);
        PaperListenerTestSupport.fire(listener, name);
        Mockito.verify(name, Mockito.never()).getEntity();
        Mockito.verify(name, Mockito.never()).getName();
        Mockito.verify(name, Mockito.never()).isPersistent();

        Assertions.assertTrue(api.submissions.isEmpty());
    }

    private static PaperEntityStateChangeListener listener(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        return PaperEntityStateChangeListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static Player player(World world) {
        return stubEntity(
            Mockito.mock(Player.class),
            world,
            EntityType.PLAYER,
            PLAYER_ID,
            1.5,
            65,
            2.5
        );
    }

    private static <T extends Entity> T stubEntity(
        T entity,
        World world,
        EntityType type,
        UUID id,
        double x,
        double y,
        double z
    ) {
        Mockito.when(entity.getUniqueId()).thenReturn(id);
        Mockito.when(entity.getType()).thenReturn(type);
        Mockito.when(entity.getWorld()).thenReturn(world);
        Mockito.when(entity.getLocation()).thenReturn(new Location(world, x, y, z));
        return entity;
    }

    private static EventSubmission onlySubmission(PaperBlockEventTestSupport.RecordingApi api) {
        Assertions.assertEquals(1, api.submissions.size());
        return api.submissions.remove();
    }

    private static void assertCommon(
        EventSubmission submission,
        Key type,
        BlockPosition position
    ) {
        Assertions.assertEquals(type, submission.eventType());
        Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
        Assertions.assertEquals(PaperBlockEventTestSupport.SERVER_KEY, submission.serverKey());
        Assertions.assertEquals(Key.key("example", "world"), submission.worldKey());
        Assertions.assertEquals(position, submission.position());
    }

    private static void assertEntity(
        CompoundTag entity,
        UUID id,
        String type
    ) {
        Assertions.assertEquals(id.toString(), entity.getString("uuid").orElseThrow());
        Assertions.assertEquals(type, entity.getString("type").orElseThrow());
        Assertions.assertEquals("example:world", entity.getString("world").orElseThrow());
        var position = entity.getCompoundOrEmpty("position");
        Assertions.assertTrue(position.contains("x"));
        Assertions.assertTrue(position.contains("y"));
        Assertions.assertTrue(position.contains("z"));
    }

    private static Component decodeComponent(CompoundTag component) {
        return PaperComponentPayloadCodec.decode(
            component.getString("component").orElseThrow()
        );
    }
}
