package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.kyori.adventure.text.Component;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.velocity.testsupport.CommandTester;
import net.okocraft.kansokusha.velocity.testsupport.TestSources;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VersionCommandTest {

    private static final String PERMISSION = "kansokusha.command.version";

    private final CommandTester tester = CommandTester.of(VersionCommand.createVersionCommand());

    @Test
    void testVersionIsPrintedToConsole() throws Exception {
        ConsoleCommandSource console = TestSources.console();
        TestSources.grant(console, PERMISSION);

        Assertions.assertEquals(1, this.tester.execute(console, "version"));

        Mockito.verify(console).sendMessage(CommandMessages.VERSION_PRINT.apply(VersionCommand.UNKNOWN_VERSION));
    }

    @Test
    void testCommandIsHiddenWithUnsetPermission() {
        this.assertHidden(TestSources.console());
    }

    private void assertHidden(CommandSource source) {
        Assertions.assertThrows(CommandSyntaxException.class, () -> this.tester.execute(source, "version"));
        Mockito.verify(source, Mockito.never()).sendMessage(Mockito.any(Component.class));
    }
}
