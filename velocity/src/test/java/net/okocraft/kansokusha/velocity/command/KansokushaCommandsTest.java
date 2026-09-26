package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.velocity.testsupport.CommandTester;
import net.okocraft.kansokusha.velocity.testsupport.TestSources;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

class KansokushaCommandsTest {

    private final CommandTester tester = CommandTester.of(KansokushaCommands.createCommand());

    @Test
    void testVersionCommandIsWiredUnderKansokushaRoot() throws Exception {
        ConsoleCommandSource console = TestSources.console();
        TestSources.grant(console, "kansokusha.command", "kansokusha.command.version");

        Assertions.assertEquals(1, this.tester.execute(console, "kansokusha version"));
        Mockito.verify(console).sendMessage(CommandMessages.VERSION_PRINT.apply(VersionCommand.UNKNOWN_VERSION));
    }

    @Test
    void testCommandDefinersAreExposedForLanguageLoading() {
        Assertions.assertEquals(List.of(CommandMessages.DEFINER), KansokushaCommands.getDefiners());
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
