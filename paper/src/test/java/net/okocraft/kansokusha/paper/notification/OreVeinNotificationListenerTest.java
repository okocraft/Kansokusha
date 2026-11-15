package net.okocraft.kansokusha.paper.notification;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class OreVeinNotificationListenerTest {

    private final DiscordWebhook webhook = Mockito.mock(DiscordWebhook.class);
    private final World world = world();
    private final MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class);

    @AfterEach
    void closeBukkit() {
        this.bukkit.close();
    }

    @Test
    void testBreakingNaturalOreSendsTheVeinOnce() {
        this.setType(0, 10, 0, Material.DIAMOND_ORE);
        this.setType(1, 10, 0, Material.DIAMOND_ORE);
        var listener = this.listener(true);

        listener.onBreak(breakEvent(this.block(0, 10, 0), player(GameMode.SURVIVAL)));
        listener.onBreak(breakEvent(this.block(1, 10, 0), player(GameMode.SURVIVAL)));

        Mockito.verify(this.webhook).send("Steve found a vein of **Diamond** x2 at 0 10 0 in world");
        Mockito.verifyNoMoreInteractions(this.webhook);
    }

    @Test
    void testPlacedOreIsNotReported() {
        this.setType(0, 10, 0, Material.DIAMOND_ORE);
        var listener = this.listener(true);
        var place = Mockito.mock(BlockPlaceEvent.class);
        var block = this.block(0, 10, 0);
        Mockito.when(place.getBlockPlaced()).thenReturn(block);

        listener.onPlace(place);
        listener.onBreak(breakEvent(block, player(GameMode.SURVIVAL)));

        Mockito.verifyNoInteractions(this.webhook);
    }

    @Test
    void testCreativeSpectatorAndUnlistedBlocksAreIgnored() {
        this.setType(0, 10, 0, Material.DIAMOND_ORE);
        this.setType(5, 10, 5, Material.STONE);
        var listener = this.listener(true);

        listener.onBreak(breakEvent(this.block(0, 10, 0), player(GameMode.CREATIVE)));
        listener.onBreak(breakEvent(this.block(0, 10, 0), player(GameMode.SPECTATOR)));
        listener.onBreak(breakEvent(this.block(5, 10, 5), player(GameMode.SURVIVAL)));

        Mockito.verifyNoInteractions(this.webhook);
    }

    @Test
    void testBlocksOutsideTheCurrentRegionAreNotRead() {
        this.setType(0, 10, 0, Material.DIAMOND_ORE);
        this.setType(1, 10, 0, Material.DIAMOND_ORE);
        var listener = this.listener(false);

        listener.onBreak(breakEvent(this.block(0, 10, 0), player(GameMode.ADVENTURE)));

        Mockito.verify(this.webhook).send("Steve found a vein of **Diamond** x1 at 0 10 0 in world");
        Mockito.verify(this.world, Mockito.never()).getType(1, 10, 0);
    }

    @Test
    void testLongVeinIsReportedOnceWithItsFullSize() {
        for (var x = 0; x < 8; x++) {
            this.setType(x, 10, 0, Material.DIAMOND_ORE);
        }
        var listener = this.listener(true);

        for (var x = 0; x < 8; x++) {
            listener.onBreak(breakEvent(this.block(x, 10, 0), player(GameMode.SURVIVAL)));
            this.setType(x, 10, 0, Material.AIR);
        }

        Mockito.verify(this.webhook).send("Steve found a vein of **Diamond** x8 at 0 10 0 in world");
        Mockito.verifyNoMoreInteractions(this.webhook);
    }

    @Test
    void testRestOfVeinBeyondSearchLimitIsReportedAgain() {
        for (var x = 0; x < 8; x++) {
            this.setType(x, 10, 0, Material.DIAMOND_ORE);
        }
        // 27 blocks cover the broken ore and its neighbours, so each report counts only two ores.
        var listener = this.listener(true, 27);

        for (var x = 0; x < 8; x++) {
            listener.onBreak(breakEvent(this.block(x, 10, 0), player(GameMode.SURVIVAL)));
            this.setType(x, 10, 0, Material.AIR);
        }

        Mockito.verify(this.webhook).send("Steve found a vein of **Diamond** x2 at 0 10 0 in world");
        Mockito.verify(this.webhook).send("Steve found a vein of **Diamond** x2 at 2 10 0 in world");
        Mockito.verify(this.webhook).send("Steve found a vein of **Diamond** x2 at 4 10 0 in world");
        Mockito.verify(this.webhook).send("Steve found a vein of **Diamond** x2 at 6 10 0 in world");
        Mockito.verifyNoMoreInteractions(this.webhook);
    }

    @Test
    void testHandlersRunAtMonitorAndIgnoreCancelledEvents() throws ReflectiveOperationException {
        for (var method : new String[]{"onPlace", "onBreak"}) {
            var parameter = method.equals("onPlace") ? BlockPlaceEvent.class : BlockBreakEvent.class;
            var handler = OreVeinNotificationListener.class.getMethod(method, parameter).getAnnotation(EventHandler.class);
            Assertions.assertEquals(EventPriority.MONITOR, handler.priority());
            Assertions.assertTrue(handler.ignoreCancelled());
        }
    }

    private OreVeinNotificationListener listener(boolean ownsRegion) {
        return this.listener(ownsRegion, 100);
    }

    private OreVeinNotificationListener listener(boolean ownsRegion, int maxSearchCount) {
        this.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(Mockito.any(World.class), Mockito.anyInt(), Mockito.anyInt()))
            .thenReturn(ownsRegion);
        var settings = new KansokushaConfig.OreVein(
            Optional.of(URI.create("https://discord.com/api/webhooks/1/token")),
            Set.of(Key.key("minecraft", "diamond_ore")),
            maxSearchCount,
            "%player_name% found a vein of **%block_type%** x%vein_count% at %block_location% in %block_world%",
            Map.of(Key.key("minecraft", "diamond_ore"), "Diamond")
        );
        return new OreVeinNotificationListener(settings, this.webhook);
    }

    private void setType(int x, int y, int z, Material type) {
        Mockito.when(this.world.getType(x, y, z)).thenReturn(type);
    }

    private Block block(int x, int y, int z) {
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(this.world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(y);
        Mockito.when(block.getZ()).thenReturn(z);
        Mockito.when(block.getLocation()).thenReturn(new Location(this.world, x, y, z));
        Mockito.when(block.getType()).thenAnswer(ignored -> this.world.getType(x, y, z));
        return block;
    }

    private static World world() {
        var world = Mockito.mock(World.class);
        Mockito.when(world.getUID()).thenReturn(UUID.fromString("123e4567-e89b-12d3-a456-426614174010"));
        Mockito.when(world.getName()).thenReturn("world");
        Mockito.when(world.isChunkLoaded(Mockito.anyInt(), Mockito.anyInt())).thenReturn(true);
        Mockito.when(world.getType(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(Material.STONE);
        return world;
    }

    private static Player player(GameMode gameMode) {
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getName()).thenReturn("Steve");
        Mockito.when(player.getGameMode()).thenReturn(gameMode);
        return player;
    }

    private static BlockBreakEvent breakEvent(Block block, Player player) {
        var event = Mockito.mock(BlockBreakEvent.class);
        Mockito.when(event.getBlock()).thenReturn(block);
        Mockito.when(event.getPlayer()).thenReturn(player);
        return event;
    }
}
