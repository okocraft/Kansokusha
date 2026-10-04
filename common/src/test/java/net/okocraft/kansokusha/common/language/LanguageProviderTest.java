package net.okocraft.kansokusha.common.language;

import dev.siroshun.mcmsgdef.file.PropertiesFile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.translation.GlobalTranslator;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.search.query.SearchQueryMessages;
import net.okocraft.kansokusha.common.search.query.SearchQueryParseException;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;

class LanguageProviderTest {

    private static final Key LANGUAGE_KEY = Key.key("kansokusha", "language");

    @AfterEach
    void tearDown() {
        LanguageProvider.unload();
    }

    @Test
    void testLoadCreatesDefaultLanguageAndRegistersTranslator(@TempDir Path directory) throws Exception {
        Files.writeString(
            directory.resolve("ja.properties"),
            "kansokusha.command.version.print=Kansokusha <version>\n"
        );

        LanguageProvider.load(directory, List.of(CommandMessages.defaultMessages()));

        Assertions.assertTrue(Files.isRegularFile(directory.resolve("en.properties")));
        Assertions.assertTrue(Files.isRegularFile(directory.resolve("ja.properties")));
        Assertions.assertTrue(hasLanguageSource());
    }

    @Test
    void testUnloadRemovesTranslator(@TempDir Path directory) throws Exception {
        Files.writeString(
            directory.resolve("ja.properties"),
            "kansokusha.command.version.print=Kansokusha <version>\n"
        );
        LanguageProvider.load(directory, List.of(CommandMessages.defaultMessages()));

        LanguageProvider.unload();

        Assertions.assertFalse(hasLanguageSource());
    }

    @Test
    void testLoadPreservesCustomValuesAndAppendsMissingDefaults(@TempDir Path directory) throws Exception {
        var key = "kansokusha.command.version.print";
        Files.writeString(directory.resolve("en.properties"), key + "=Custom version <version>\n");
        Files.writeString(directory.resolve("ja.properties"), key + "=独自のバージョン <version>\n");

        LanguageProvider.load(directory, List.of(CommandMessages.defaultMessages()));

        Assertions.assertEquals("Custom version <version>", PropertiesFile.load(directory.resolve("en.properties")).get(key));
        Assertions.assertEquals("独自のバージョン <version>", PropertiesFile.load(directory.resolve("ja.properties")).get(key));
        Assertions.assertTrue(PropertiesFile.load(directory.resolve("en.properties")).containsKey("kansokusha.command.help.header"));
        Assertions.assertTrue(PropertiesFile.load(directory.resolve("ja.properties")).containsKey("kansokusha.command.help.header"));
    }

    @Test
    void testLoadCreatesBothBaselineLocalesAndFallsBackToEnglish(@TempDir Path directory) throws Exception {
        LanguageProvider.load(directory, List.of(CommandMessages.defaultMessages()));

        Assertions.assertTrue(Files.isRegularFile(directory.resolve("en.properties")));
        Assertions.assertTrue(Files.isRegularFile(directory.resolve("ja.properties")));
        var message = CommandMessages.HELP_HEADER.asComponent();
        Assertions.assertEquals(plain(GlobalTranslator.render(message, Locale.ENGLISH)),
            plain(GlobalTranslator.render(message, Locale.FRENCH)));
        Assertions.assertNotEquals(plain(GlobalTranslator.render(message, Locale.ENGLISH)),
            plain(GlobalTranslator.render(message, Locale.JAPANESE)));
    }

    @Test
    void testReloadReplacesSourceAndFailedLoadKeepsCurrentSource(@TempDir Path directory) throws Exception {
        var defaults = List.of(CommandMessages.defaultMessages());
        LanguageProvider.load(directory, defaults);
        var original = StreamSupport.stream(GlobalTranslator.translator().sources().spliterator(), false)
            .filter(source -> source.name().equals(LANGUAGE_KEY)).findFirst().orElseThrow();
        Files.writeString(directory.resolve("en.properties"), "kansokusha.command.help.header=Updated help\n");

        LanguageProvider.load(directory, defaults);

        Assertions.assertEquals(1, languageSourceCount());
        Assertions.assertFalse(StreamSupport.stream(GlobalTranslator.translator().sources().spliterator(), false)
            .anyMatch(source -> source == original));
        var message = CommandMessages.HELP_HEADER.asComponent();
        Assertions.assertEquals("Updated help", plain(GlobalTranslator.render(message, Locale.ENGLISH)));
        var invalidDirectory = directory.resolve("not-a-directory");
        Files.writeString(invalidDirectory, "file");
        Assertions.assertThrows(IOException.class, () -> LanguageProvider.load(invalidDirectory, defaults));
        Assertions.assertEquals(1, languageSourceCount());
        Assertions.assertEquals("Updated help", plain(GlobalTranslator.render(message, Locale.ENGLISH)));
    }

    @Test
    void testDuplicateKeysAreRejectedBeforeReplacingSource(@TempDir Path directory) throws Exception {
        LanguageProvider.load(directory, List.of(CommandMessages.defaultMessages()));
        Assertions.assertThrows(IllegalArgumentException.class, () -> LanguageProvider.load(directory,
            List.of(Map.of("duplicate", "one"), Map.of("duplicate", "two"))));
        Assertions.assertEquals(1, languageSourceCount());
    }

    @Test
    void testParseReasonsRenderInBothLocalesWithoutInterpretingUserTags(@TempDir Path directory) throws Exception {
        LanguageProvider.load(directory, List.of(SearchCommandMessages.defaultMessages(), SearchQueryMessages.defaultMessages()));
        var failure = Assertions.assertThrows(SearchQueryParseException.class, () ->
            SearchQueryParser.parse("order '<red>sideways</red>'", Clock.systemUTC(), ZoneOffset.UTC, null));
        var message = SearchCommandMessages.PARSE_ERROR.apply(failure.reason());
        var english = plain(GlobalTranslator.render(message, Locale.ENGLISH));
        var japanese = plain(GlobalTranslator.render(message, Locale.JAPANESE));

        Assertions.assertTrue(english.contains("Specify newest or oldest."), english);
        Assertions.assertTrue(japanese.contains("newest または oldest を指定してください。"), japanese);
        Assertions.assertTrue(english.contains("<red>sideways</red>"), english);
        Assertions.assertTrue(japanese.contains("<red>sideways</red>"), japanese);
    }

    @Test
    void testRequiredArgumentHelpRendersLiterally(@TempDir Path directory) throws Exception {
        LanguageProvider.load(directory, List.of(CommandMessages.defaultMessages()));
        for (var locale : List.of(Locale.ENGLISH, Locale.JAPANESE)) {
            Assertions.assertTrue(plain(GlobalTranslator.render(CommandMessages.HELP_EVENT.asComponent(), locale))
                .contains("/kansokusha event <event-id>"));
        }
    }

    private static long languageSourceCount() {
        return StreamSupport.stream(GlobalTranslator.translator().sources().spliterator(), false)
            .filter(source -> source.name().equals(LANGUAGE_KEY)).count();
    }

    private static String plain(Component component) {
        var text = new StringBuilder(component instanceof TextComponent value ? value.content() : "");
        component.children().forEach(child -> text.append(plain(child)));
        return text.toString();
    }

    private static boolean hasLanguageSource() {
        return StreamSupport.stream(
            GlobalTranslator.translator().sources().spliterator(),
            false
        ).anyMatch(source -> source.name().equals(LANGUAGE_KEY));
    }
}
