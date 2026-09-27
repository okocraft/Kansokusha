package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kyori.adventure.text.ComponentLike;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VersionCommandTest {

    private static final String PERMISSION = "kansokusha.command.version";

    private final CommandTester tester = CommandTester.of(VersionCommand.createVersionCommand());

    @Test
    void testVersionIsPrintedToConsole() throws Exception {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(console, PERMISSION);

        Assertions.assertEquals(1, this.tester.execute(TestSources.ofSenderOnly(console), "version"));

        Mockito.verify(console).sendMessage(CommandMessages.VERSION_PRINT.apply(VersionCommand.UNKNOWN_VERSION));
    }

    @Test
    void testCommandIsHiddenWithUnsetPermission() {
        this.assertHidden(Mockito.mock(ConsoleCommandSender.class));
    }

    private void assertHidden(ConsoleCommandSender console) {
        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(TestSources.ofSenderOnly(console), "version")
        );
        Mockito.verify(console, Mockito.never()).sendMessage(Mockito.any(ComponentLike.class));
    }
}
