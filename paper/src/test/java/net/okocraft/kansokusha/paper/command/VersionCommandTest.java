package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kyori.adventure.text.ComponentLike;
import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
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

        Mockito.verify(console).sendMessage(VersionCommand.versionMessage(VersionCommand.UNKNOWN_VERSION));
    }

    @Test
    void testVersionIsPrintedToPlayer() throws Exception {
        Player player = Mockito.mock(Player.class);
        TestSources.grant(player, PERMISSION);

        Assertions.assertEquals(1, this.tester.execute(TestSources.of(player), "version"));

        Mockito.verify(player).sendMessage(VersionCommand.versionMessage(VersionCommand.UNKNOWN_VERSION));
    }

    @Test
    void testCommandIsHiddenWithUnsetPermission() {
        this.assertHidden(Mockito.mock(ConsoleCommandSender.class));
    }

    @Test
    void testCommandIsHiddenWithDeniedPermission() {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.deny(console, PERMISSION);

        this.assertHidden(console);
    }

    @Test
    void testCommandIsHiddenWithAnotherPermission() {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(console, "kansokusha.command");

        this.assertHidden(console);
    }

    private void assertHidden(ConsoleCommandSender console) {
        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(TestSources.ofSenderOnly(console), "version")
        );
        Mockito.verify(console, Mockito.never()).sendMessage(Mockito.any(ComponentLike.class));
    }
}
