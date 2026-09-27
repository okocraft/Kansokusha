package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.velocity.testsupport.CommandTester;
import net.okocraft.kansokusha.velocity.testsupport.TestSources;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

class KansokushaCommandsTest {

    private final EventSearchBackend backend = Mockito.mock(EventSearchBackend.class);
    private final CommandTester tester = CommandTester.of(
        KansokushaCommands.createCommand(this.backend, Clock.systemUTC(), ZoneOffset.UTC)
    );

    @Test
    void testVersionCommandIsWiredUnderKansokushaRoot() throws Exception {
        ConsoleCommandSource console = TestSources.console();
        TestSources.grant(console, "kansokusha.command", "kansokusha.command.version");

        Assertions.assertEquals(1, this.tester.execute(console, "kansokusha version"));
        Mockito.verify(console).sendMessage(CommandMessages.VERSION_PRINT.apply(VersionCommand.UNKNOWN_VERSION));
    }

    @Test
    void testCommandDefinersAreExposedForLanguageLoading() {
        Assertions.assertEquals(
            List.of(
                CommandMessages.DEFINER,
                SearchCommandMessages.DEFINER,
                EventCommandMessages.DEFINER
            ),
            KansokushaCommands.getDefiners()
        );
    }

    @Test
    void testSearchCommandIsWiredUnderKansokushaRoot() throws Exception {
        ConsoleCommandSource console = TestSources.console();
        TestSources.grant(console, "kansokusha.command", SearchCommand.PERMISSION);
        Mockito.when(this.backend.searchMetadata())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("stopped")));

        Assertions.assertEquals(1, this.tester.execute(console, "kansokusha search"));
        Mockito.verify(console).sendMessage(SearchCommandMessages.SEARCH_FAILED.asComponent());
    }

    @Test
    void testEventCommandIsWiredUnderKansokushaRoot() {
        Assertions.assertNotNull(
            KansokushaCommands.createCommand(this.backend, Clock.systemUTC(), ZoneOffset.UTC)
                .getNode()
                .getChild("event")
        );
    }

    @Test
    void testRootCommandIsHiddenWithoutPermission() {
        ConsoleCommandSource console = TestSources.console();
        TestSources.grant(console, "kansokusha.command.version");

        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(console, "kansokusha version")
        );
    }
}
