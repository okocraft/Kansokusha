package net.okocraft.kansokusha.common.language;

import dev.siroshun.mcmsgdef.directory.DirectorySource;
import dev.siroshun.mcmsgdef.directory.MessageProcessors;
import dev.siroshun.mcmsgdef.file.PropertiesFile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.Translator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LanguageProvider {

    private static final Key LANGUAGE_KEY = Key.key("kansokusha", "language");

    private static Translator messageSource;

    public static void load(Path directory, List<Map<String, String>> defaultMessages) throws IOException {
        Map<String, String> messageMap = new LinkedHashMap<>();
        for (var defaults : defaultMessages) {
            for (var entry : defaults.entrySet()) {
                if (messageMap.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalArgumentException("Duplicate message key: " + entry.getKey());
                }
            }
        }

        Translator nextSource = DirectorySource.propertiesFiles(directory)
            .defaultLocale(Locale.ENGLISH, Locale.JAPANESE)
            .primaryLocale(Locale.ENGLISH)
            .messageProcessor(MessageProcessors.appendMissingMessagesToPropertiesFile(locale -> {
                if (locale.equals(Locale.ENGLISH)) {
                    return messageMap;
                }

                try (InputStream input = LanguageProvider.class.getClassLoader()
                    .getResourceAsStream("languages/" + locale + ".properties")) {
                    return input != null ? PropertiesFile.load(input) : null;
                }
            }))
            .loadAsMiniMessageTranslationStore(LANGUAGE_KEY);

        unload();
        GlobalTranslator.translator().addSource(nextSource);
        messageSource = nextSource;
    }

    public static void unload() {
        if (messageSource != null) {
            GlobalTranslator.translator().removeSource(messageSource);
            messageSource = null;
        }
    }

    private LanguageProvider() {
        throw new UnsupportedOperationException();
    }
}
