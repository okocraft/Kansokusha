package net.okocraft.kansokusha.common.language;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.GlobalTranslator;
import net.okocraft.kansokusha.common.command.CommandMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        LanguageProvider.load(directory, List.of(CommandMessages.DEFINER));

        Assertions.assertTrue(Files.isRegularFile(directory.resolve("en.properties")));
        Assertions.assertTrue(Files.isRegularFile(directory.resolve("ja.properties")));
        Assertions.assertTrue(
            GlobalTranslator.translator().sources().stream()
                .anyMatch(source -> source.name().equals(LANGUAGE_KEY))
        );
    }

    @Test
    void testUnloadRemovesTranslator(@TempDir Path directory) throws Exception {
        Files.writeString(
            directory.resolve("ja.properties"),
            "kansokusha.command.version.print=Kansokusha <version>\n"
        );
        LanguageProvider.load(directory, List.of(CommandMessages.DEFINER));

        LanguageProvider.unload();

        Assertions.assertFalse(
            GlobalTranslator.translator().sources().stream()
                .anyMatch(source -> source.name().equals(LANGUAGE_KEY))
        );
    }
}
