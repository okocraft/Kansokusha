package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    void testRootAndHelpShowOnlyPermittedCommands() throws Exception {
        var console = TestSources.console();
        TestSources.grant(console, "kansokusha.command", "kansokusha.command.version");

        Assertions.assertEquals(1, this.tester.execute(console, "kansokusha"));
        Assertions.assertEquals(1, this.tester.execute(console, "kansokusha help"));

        Mockito.verify(console, Mockito.times(2)).sendMessage(CommandMessages.HELP_HEADER.asComponent());
        Mockito.verify(console, Mockito.times(2)).sendMessage(CommandMessages.HELP_VERSION.asComponent());
        Mockito.verify(console, Mockito.never()).sendMessage(CommandMessages.HELP_SEARCH.asComponent());
        Mockito.verify(console, Mockito.never()).sendMessage(CommandMessages.HELP_EVENT.asComponent());
    }

    @Test
    void testEventWithoutIdShowsUsage() throws Exception {
        var console = TestSources.console();
        TestSources.grant(console, "kansokusha.command", "kansokusha.command.event");

        Assertions.assertEquals(0, this.tester.execute(console, "kansokusha event"));

        Mockito.verify(console).sendMessage(CommandMessages.HELP_EVENT.asComponent());
        Mockito.verifyNoInteractions(this.backend);
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

        var keys = new HashSet<String>();
        var miniMessage = MiniMessage.builder().strict(true).build();
        for (var defaults : KansokushaCommands.getDefaultMessages()) {
            for (var entry : defaults.entrySet()) {
                var key = entry.getKey();
                Assertions.assertTrue(keys.add(key), "Duplicate key: " + key);
                var japanese = properties.getProperty(key);
                Assertions.assertNotNull(japanese, key);
                var placeholders = placeholderNames(entry.getValue());
                Assertions.assertEquals(placeholders, placeholderNames(japanese), key);
                var resolver = TagResolver.builder();
                placeholders.forEach(name -> resolver.resolver(Placeholder.unparsed(name, "sample")));
                var arguments = resolver.build();
                Assertions.assertDoesNotThrow(() -> miniMessage.deserialize(entry.getValue(), arguments), key);
                Assertions.assertDoesNotThrow(() -> miniMessage.deserialize(japanese, arguments), key);
            }
        }
        Assertions.assertEquals(keys, properties.stringPropertyNames());
    }

    private static Set<String> placeholderNames(String message) {
        return Pattern.compile("(?<!\\\\)<([a-z_]+)>").matcher(message).results()
            .map(match -> match.group(1))
            .filter(name -> !TagResolver.standard().has(name))
            .collect(Collectors.toSet());
    }
}
