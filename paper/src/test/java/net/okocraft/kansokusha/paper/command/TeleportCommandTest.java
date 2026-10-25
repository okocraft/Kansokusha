package net.okocraft.kansokusha.paper.command;

import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TeleportCommandTest {
    @Test
    void testTeleportRunsOnPlayerSchedulerAndKeepsOrientation() throws Exception {
        var plugin = Mockito.mock(Plugin.class);
        var server = Mockito.mock(Server.class);
        var world = Mockito.mock(World.class);
        var player = Mockito.mock(Player.class);
        var scheduler = Mockito.mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        Mockito.when(plugin.getServer()).thenReturn(server);
        Mockito.when(server.getWorld(NamespacedKey.minecraft("overworld"))).thenReturn(world);
        Mockito.when(player.getScheduler()).thenReturn(scheduler);
        Mockito.when(player.getLocation()).thenReturn(new Location(world, 0, 0, 0, 45, 30));
        Mockito.when(player.teleportAsync(Mockito.any(Location.class)))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
        Mockito.when(scheduler.execute(Mockito.eq(plugin), Mockito.any(), Mockito.isNull(), Mockito.eq(1L)))
            .thenAnswer(call -> { call.getArgument(1, Runnable.class).run(); return true; });
        TestSources.grant(player, TeleportCommand.PERMISSION);
        var tester = CommandTester.of(TeleportCommand.create(plugin).build());
        Assertions.assertEquals(1, tester.execute(TestSources.of(player), "teleport minecraft:overworld 10 64 -8"));
        var location = ArgumentCaptor.forClass(Location.class);
        Mockito.verify(player).teleportAsync(location.capture());
        Assertions.assertEquals(10.5, location.getValue().getX());
        Assertions.assertEquals(-7.5, location.getValue().getZ());
        Assertions.assertEquals(64, location.getValue().getY());
        Assertions.assertEquals(45, location.getValue().getYaw());
        Assertions.assertEquals(30, location.getValue().getPitch());
        Assertions.assertEquals(1, tester.execute(TestSources.of(player), "teleport minecraft:missing 10 64 -8"));
        Mockito.verify(player).sendMessage(TeleportMessages.WORLD_UNAVAILABLE.asComponent());
        Mockito.verify(player, Mockito.times(1)).teleportAsync(Mockito.any(Location.class));
    }

    @Test
    void testConsoleIsRejected() throws Exception {
        var console = Mockito.mock(org.bukkit.command.ConsoleCommandSender.class);
        TestSources.grant(console, TeleportCommand.PERMISSION);
        Assertions.assertEquals(0, CommandTester.of(TeleportCommand.create(Mockito.mock(Plugin.class)).build())
            .execute(TestSources.ofSenderOnly(console), "teleport minecraft:overworld 1 64 2"));
        Mockito.verify(console).sendMessage(TeleportMessages.PLAYER_ONLY.asComponent());
    }
}
