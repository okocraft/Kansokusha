package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.paper.inspection.InspectionSessionManager;
import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

class InspectCommandTest {

    private final EventSearchBackend backend = Mockito.mock(EventSearchBackend.class);
    private final InspectionSessionManager sessions = new InspectionSessionManager();
    private final CommandTester tester = CommandTester.of(KansokushaCommands.createCommand(
        this.backend,
        Clock.systemUTC(),
        ZoneOffset.UTC,
        this.sessions,
        Mockito.mock(org.bukkit.plugin.Plugin.class)
    ));

    @Test
    void testInspectWithoutArgumentTogglesCurrentSession() throws Exception {
        var player = player();

        Assertions.assertEquals(1, this.tester.execute(TestSources.of(player), "kansokusha inspect"));
        Assertions.assertTrue(this.sessions.isEnabled(player.getUniqueId()));
        Mockito.verify(player).sendMessage(InspectionCommandMessages.ENABLED.asComponent());

        Assertions.assertEquals(1, this.tester.execute(TestSources.of(player), "kansokusha inspect"));
        Assertions.assertFalse(this.sessions.isEnabled(player.getUniqueId()));
        Mockito.verify(player).sendMessage(InspectionCommandMessages.DISABLED.asComponent());
    }

    @Test
    void testExplicitOnAndOffAreIdempotent() throws Exception {
        var player = player();

        Assertions.assertEquals(1, this.tester.execute(TestSources.of(player), "kansokusha inspect on"));
        Assertions.assertEquals(1, this.tester.execute(TestSources.of(player), "kansokusha inspect on"));
        Assertions.assertTrue(this.sessions.isEnabled(player.getUniqueId()));

        Assertions.assertEquals(1, this.tester.execute(TestSources.of(player), "kansokusha inspect off"));
        Assertions.assertEquals(1, this.tester.execute(TestSources.of(player), "kansokusha inspect off"));
        Assertions.assertFalse(this.sessions.isEnabled(player.getUniqueId()));

        Mockito.verify(player, Mockito.times(2)).sendMessage(InspectionCommandMessages.ENABLED.asComponent());
        Mockito.verify(player, Mockito.times(2)).sendMessage(InspectionCommandMessages.DISABLED.asComponent());
    }

    @Test
    void testAliasUsesSameSessionState() throws Exception {
        var player = player();

        Assertions.assertEquals(1, this.tester.execute(TestSources.of(player), "kansokusha inspect on"));
        Assertions.assertTrue(this.sessions.isEnabled(player.getUniqueId()));

        Assertions.assertEquals(1, this.tester.execute(TestSources.of(player), "kansokusha i"));
        Assertions.assertFalse(this.sessions.isEnabled(player.getUniqueId()));
    }

    @Test
    void testConsoleIsRejected() throws Exception {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(
            console,
            "kansokusha.command",
            InspectionSessionManager.PERMISSION
        );

        Assertions.assertEquals(
            0,
            this.tester.execute(TestSources.ofSenderOnly(console), "kansokusha inspect")
        );
        Mockito.verify(console).sendMessage(InspectionCommandMessages.PLAYER_ONLY.asComponent());
    }

    @Test
    void testInspectPermissionIsRequired() {
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        TestSources.grant(player, "kansokusha.command");
        TestSources.deny(player, InspectionSessionManager.PERMISSION);

        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(TestSources.of(player), "kansokusha inspect")
        );
        Assertions.assertFalse(this.sessions.isEnabled(player.getUniqueId()));
    }

    @Test
    void testHiddenPageCommandUsesOnlyInspectAndRejectsArbitraryQueries() throws Exception {
        var player = player();
        var server = Mockito.mock(org.bukkit.Server.class);
        Mockito.when(player.getServer()).thenReturn(server);
        Mockito.when(server.getPlayer(player.getUniqueId())).thenReturn(player);
        Mockito.when(this.backend.searchMetadata()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
            net.okocraft.kansokusha.common.search.SearchMetadata.empty()));
        Mockito.when(this.backend.search(Mockito.any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
            new net.okocraft.kansokusha.common.search.SearchPage(java.util.List.of(), java.util.Optional.empty(), java.util.Optional.empty())));
        var source = TestSources.of(player);
        Assertions.assertFalse(this.tester.suggest(source, "kansokusha inspect ").contains("page"));
        Assertions.assertEquals(1, this.tester.execute(source, "kansokusha inspect page minecraft:overworld 1 64 2 first"));
        var request = org.mockito.ArgumentCaptor.forClass(net.okocraft.kansokusha.common.search.SearchRequest.class);
        Mockito.verify(this.backend).search(request.capture());
        Assertions.assertEquals(java.util.Set.of(new net.okocraft.kansokusha.common.search.query.SearchQuery.Position(
            net.kyori.adventure.key.Key.key("minecraft:overworld"), 1, 64, 2)), request.getValue().query().conditions().positions());
        Assertions.assertEquals(0, this.tester.execute(source, "kansokusha inspect page user Alice"));
        Mockito.verify(this.backend, Mockito.times(1)).search(Mockito.any());
    }

    private static Player player() {
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        TestSources.grant(
            player,
            "kansokusha.command",
            InspectionSessionManager.PERMISSION
        );
        return player;
    }
}
