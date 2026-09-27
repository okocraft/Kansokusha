package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.paper.inspection.InspectionSearchMessages;
import net.okocraft.kansokusha.paper.inspection.InspectionSessionManager;
import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

class KansokushaCommandsTest {

    private final EventSearchBackend backend = Mockito.mock(EventSearchBackend.class);
    private final CommandTester tester = CommandTester.of(KansokushaCommands.createCommand(
        this.backend,
        Clock.systemUTC(),
        ZoneOffset.UTC,
        new InspectionSessionManager()
    ));

    @Test
    void testVersionCommandIsWiredUnderKansokushaRoot() throws Exception {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(console, "kansokusha.command", "kansokusha.command.version");

        Assertions.assertEquals(
            1,
            this.tester.execute(TestSources.ofSenderOnly(console), "kansokusha version")
        );
        Mockito.verify(console).sendMessage(CommandMessages.VERSION_PRINT.apply(VersionCommand.UNKNOWN_VERSION));
    }

    @Test
    void testCommandDefinersAreExposedForLanguageLoading() {
        Assertions.assertEquals(
            List.of(
                CommandMessages.DEFINER,
                SearchCommandMessages.DEFINER,
                EventCommandMessages.DEFINER,
                InspectionCommandMessages.DEFINER,
                InspectionSearchMessages.DEFINER
            ),
            KansokushaCommands.getDefiners()
        );
    }

    @Test
    void testSearchCommandIsWiredUnderKansokushaRoot() throws Exception {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(console, "kansokusha.command", SearchCommand.PERMISSION);
        Mockito.when(this.backend.searchMetadata())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("stopped")));

        Assertions.assertEquals(
            1,
            this.tester.execute(TestSources.ofSenderOnly(console), "kansokusha search")
        );
        Mockito.verify(console).sendMessage(SearchCommandMessages.SEARCH_FAILED.asComponent());
    }

    @Test
    void testSubcommandsAreWiredUnderKansokushaRoot() {
        var command = KansokushaCommands.createCommand(
            this.backend,
            Clock.systemUTC(),
            ZoneOffset.UTC,
            new InspectionSessionManager()
        );

        for (var name : List.of("version", "search", "event", "inspect", "i")) {
            Assertions.assertNotNull(command.getChild(name), name);
        }
    }

    @Test
    void testRootCommandIsHiddenWithoutPermission() {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(console, "kansokusha.command.version");

        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(TestSources.ofSenderOnly(console), "kansokusha version")
        );
    }
}
