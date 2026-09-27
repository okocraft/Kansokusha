package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.paper.inspection.InspectionSessionManager;
import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

class KansokushaCommandsTest {

    private final CommandTester tester = CommandTester.of(KansokushaCommands.createCommand());

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
                InspectionCommandMessages.DEFINER
            ),
            KansokushaCommands.getDefiners()
        );
    }

    @Test
    void testSearchCommandIsWiredUnderKansokushaRoot() throws Exception {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(console, "kansokusha.command", SearchCommand.PERMISSION);

        Assertions.assertEquals(
            0,
            this.tester.execute(TestSources.ofSenderOnly(console), "kansokusha search")
        );
        Mockito.verify(console).sendMessage(SearchCommandMessages.SEARCH_FAILED.asComponent());
    }

    @Test
    void testEventCommandIsWiredUnderKansokushaRoot() {
        Assertions.assertNotNull(KansokushaCommands.createCommand().getChild("event"));
    }

    @Test
    void testLegacyCommandCreationDoesNotExposeInspectionWithoutSessionLifecycle() {
        var command = KansokushaCommands.createCommand();

        Assertions.assertNull(command.getChild("inspect"));
        Assertions.assertNull(command.getChild("i"));
    }

    @Test
    void testInspectCommandsAreWiredWhenSessionManagerIsProvided() {
        var command = KansokushaCommands.createCommand(new InspectionSessionManager());

        Assertions.assertNotNull(command.getChild("inspect"));
        Assertions.assertNotNull(command.getChild("i"));
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
