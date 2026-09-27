package net.okocraft.kansokusha.paper.inspection;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

class InspectionListenerTest {

    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174100");

    @Test
    void testLeftClickResolvesClickedCoordinateAndSuppressesVanillaUse() {
        var fixture = fixture();
        var clicked = block(10, 64, -3);
        var event = interaction(fixture.player(), Action.LEFT_CLICK_BLOCK, clicked, BlockFace.UP, EquipmentSlot.HAND);

        fixture.listener().inspect(event);

        assertSuppressed(event);
        Assertions.assertEquals(
            List.of(new SearchQuery.Position(Key.key("example", "world"), 10, 64, -3)),
            fixture.targets()
        );
    }

    @Test
    void testRightClickResolvesAdjacentCoordinateForAllFaces() {
        for (var face : List.of(
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN
        )) {
            var fixture = fixture();
            var clicked = block(10, 64, -3);
            var relative = block(
                10 + face.getModX(),
                64 + face.getModY(),
                -3 + face.getModZ()
            );
            Mockito.when(clicked.getRelative(face)).thenReturn(relative);
            var event = interaction(
                fixture.player(),
                Action.RIGHT_CLICK_BLOCK,
                clicked,
                face,
                EquipmentSlot.HAND
            );

            fixture.listener().inspect(event);

            assertSuppressed(event);
            Assertions.assertEquals(
                List.of(new SearchQuery.Position(
                    Key.key("example", "world"),
                    10 + face.getModX(),
                    64 + face.getModY(),
                    -3 + face.getModZ()
                )),
                fixture.targets(),
                face.name()
            );
        }
    }

    @Test
    void testOffHandIsSuppressedWithoutDuplicateTarget() {
        var fixture = fixture();
        var clicked = block(10, 64, -3);
        var event = interaction(fixture.player(), Action.LEFT_CLICK_BLOCK, clicked, BlockFace.UP, EquipmentSlot.OFF_HAND);

        fixture.listener().inspect(event);

        assertSuppressed(event);
        Assertions.assertTrue(fixture.targets().isEmpty());
    }

    @Test
    void testAirClicksAreIgnored() {
        var fixture = fixture();

        var leftAir = interaction(fixture.player(), Action.LEFT_CLICK_AIR, null, BlockFace.SELF, EquipmentSlot.HAND);
        var rightAir = interaction(fixture.player(), Action.RIGHT_CLICK_AIR, null, BlockFace.SELF, EquipmentSlot.HAND);

        fixture.listener().inspect(leftAir);
        fixture.listener().inspect(rightAir);

        Mockito.verify(leftAir, Mockito.never()).setUseInteractedBlock(Mockito.any());
        Mockito.verify(leftAir, Mockito.never()).setUseItemInHand(Mockito.any());
        Mockito.verify(rightAir, Mockito.never()).setUseInteractedBlock(Mockito.any());
        Mockito.verify(rightAir, Mockito.never()).setUseItemInHand(Mockito.any());
        Assertions.assertTrue(fixture.targets().isEmpty());
    }

    @Test
    void testInspectionOffLeavesInteractionUntouched() {
        var sessions = new InspectionSessionManager();
        var targets = new CopyOnWriteArrayList<SearchQuery.Position>();
        var listener = new InspectionListener(
            sessions,
            (playerId, target) -> targets.add(target)
        );
        var player = player();
        var event = interaction(player, Action.LEFT_CLICK_BLOCK, block(1, 2, 3), BlockFace.UP, EquipmentSlot.HAND);

        listener.inspect(event);

        Mockito.verify(event, Mockito.never()).setUseInteractedBlock(Mockito.any());
        Mockito.verify(event, Mockito.never()).setUseItemInHand(Mockito.any());
        Assertions.assertTrue(targets.isEmpty());
    }

    @Test
    void testRevokedPermissionDisablesSessionAndLeavesInteractionUntouched() {
        var sessions = new InspectionSessionManager();
        var targets = new CopyOnWriteArrayList<SearchQuery.Position>();
        var listener = new InspectionListener(
            sessions,
            (playerId, target) -> targets.add(target)
        );
        var player = player();
        sessions.enable(PLAYER_ID);
        TestSources.deny(player, InspectionSessionManager.PERMISSION);
        var event = interaction(player, Action.LEFT_CLICK_BLOCK, block(1, 2, 3), BlockFace.UP, EquipmentSlot.HAND);

        listener.inspect(event);

        Assertions.assertFalse(sessions.isEnabled(PLAYER_ID));
        Mockito.verify(event, Mockito.never()).setUseInteractedBlock(Mockito.any());
        Mockito.verify(event, Mockito.never()).setUseItemInHand(Mockito.any());
        Assertions.assertTrue(targets.isEmpty());
    }

    @Test
    void testAlreadyCancelledInteractionStillInspectsAndSuppressesBothUses() {
        var fixture = fixture();
        var event = interaction(
            fixture.player(),
            Action.LEFT_CLICK_BLOCK,
            block(4, 5, 6),
            BlockFace.UP,
            EquipmentSlot.HAND
        );
        Mockito.when(event.isCancelled()).thenReturn(true);

        fixture.listener().inspect(event);

        assertSuppressed(event);
        Assertions.assertEquals(
            List.of(new SearchQuery.Position(Key.key("example", "world"), 4, 5, 6)),
            fixture.targets()
        );
    }

    @Test
    void testDamageAndBreakAreCancelledForInspector() {
        var fixture = fixture();

        var damage = Mockito.mock(BlockDamageEvent.class);
        Mockito.when(damage.getPlayer()).thenReturn(fixture.player());
        fixture.listener().suppressDamage(damage);
        Mockito.verify(damage).setCancelled(true);

        var breakEvent = Mockito.mock(BlockBreakEvent.class);
        Mockito.when(breakEvent.getPlayer()).thenReturn(fixture.player());
        fixture.listener().suppressBreak(breakEvent);
        Mockito.verify(breakEvent).setCancelled(true);
    }

    @Test
    void testDamageAndBreakAreUntouchedWhenInspectionIsOff() {
        var sessions = new InspectionSessionManager();
        var listener = new InspectionListener(sessions, (playerId, target) -> {
        });
        var player = player();

        var damage = Mockito.mock(BlockDamageEvent.class);
        Mockito.when(damage.getPlayer()).thenReturn(player);
        listener.suppressDamage(damage);
        Mockito.verify(damage, Mockito.never()).setCancelled(true);

        var breakEvent = Mockito.mock(BlockBreakEvent.class);
        Mockito.when(breakEvent.getPlayer()).thenReturn(player);
        listener.suppressBreak(breakEvent);
        Mockito.verify(breakEvent, Mockito.never()).setCancelled(true);
    }

    @Test
    void testCallbackReceivesOnlyPlayerIdAndImmutableSnapshot() {
        var sessions = new InspectionSessionManager();
        var player = player();
        sessions.enable(PLAYER_ID);

        var ids = new CopyOnWriteArrayList<UUID>();
        var targets = new CopyOnWriteArrayList<SearchQuery.Position>();
        var listener = new InspectionListener(sessions, (playerId, target) -> {
            ids.add(playerId);
            targets.add(target);
        });
        var clicked = block(9, 70, 11);
        var event = interaction(player, Action.LEFT_CLICK_BLOCK, clicked, BlockFace.UP, EquipmentSlot.HAND);

        listener.inspect(event);

        Assertions.assertEquals(List.of(PLAYER_ID), ids);
        Assertions.assertEquals(
            List.of(new SearchQuery.Position(Key.key("example", "world"), 9, 70, 11)),
            targets
        );
    }

    @Test
    void testQuitDisablesInspectionForPlayer() {
        var sessions = new InspectionSessionManager();
        var player = player();
        sessions.enable(PLAYER_ID);
        var event = Mockito.mock(PlayerQuitEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);

        new InspectionListener(sessions, (playerId, target) -> {
        }).cleanup(event);

        Assertions.assertFalse(sessions.isEnabled(PLAYER_ID));
    }

    private static Fixture fixture() {
        var sessions = new InspectionSessionManager();
        var player = player();
        sessions.enable(PLAYER_ID);
        var targets = new CopyOnWriteArrayList<SearchQuery.Position>();
        return new Fixture(
            player,
            targets,
            new InspectionListener(
                sessions,
                (playerId, target) -> {
                    Assertions.assertEquals(PLAYER_ID, playerId);
                    targets.add(target);
                }
            )
        );
    }

    private static Player player() {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        TestSources.grant(player, InspectionSessionManager.PERMISSION);
        return player;
    }

    private static Block block(int x, int y, int z) {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(new NamespacedKey("example", "world"));

        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(y);
        Mockito.when(block.getZ()).thenReturn(z);
        return block;
    }

    private static PlayerInteractEvent interaction(
        Player player,
        Action action,
        Block clicked,
        BlockFace face,
        EquipmentSlot hand
    ) {
        var event = Mockito.mock(PlayerInteractEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getAction()).thenReturn(action);
        Mockito.when(event.getClickedBlock()).thenReturn(clicked);
        Mockito.when(event.getBlockFace()).thenReturn(face);
        Mockito.when(event.getHand()).thenReturn(hand);
        return event;
    }

    private static void assertSuppressed(PlayerInteractEvent event) {
        Mockito.verify(event).setUseInteractedBlock(Event.Result.DENY);
        Mockito.verify(event).setUseItemInHand(Event.Result.DENY);
    }

    private record Fixture(
        Player player,
        CopyOnWriteArrayList<SearchQuery.Position> targets,
        InspectionListener listener
    ) {
    }
}
