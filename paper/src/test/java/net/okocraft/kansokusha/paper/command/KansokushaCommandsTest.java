package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
        Mockito.verify(console).sendMessage(VersionCommand.versionMessage(VersionCommand.UNKNOWN_VERSION));
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
