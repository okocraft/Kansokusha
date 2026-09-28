package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.velocity.testsupport.CommandTester;
import net.okocraft.kansokusha.velocity.testsupport.TestSources;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Properties;

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

    @Test
    void testJapaneseBundleContainsEveryMessageKey() throws Exception {
        var properties = new Properties();
        try (var input = KansokushaCommandsTest.class.getClassLoader().getResourceAsStream("languages/ja.properties")) {
            Assertions.assertNotNull(input);
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }

        for (var definer : KansokushaCommands.getDefiners()) {
            Assertions.assertTrue(properties.stringPropertyNames().containsAll(definer.getCollectedMessages().keySet()));
        }
    }
}
