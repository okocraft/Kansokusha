package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.translation.GlobalTranslator;
import net.okocraft.kansokusha.common.language.LanguageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

class EventTimeTest {

    @AfterEach
    void cleanup() {
        LanguageProvider.unload();
    }

    @Test
    void testBoundariesFutureAndLocales(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, List.of(EventPresentationMessages.defaultMessages()));
        var now = Instant.parse("2026-10-25T00:00:00Z");
        var timezone = ZoneId.of("Asia/Tokyo");
        var seconds = new long[]{0, 59, 60, 3599, 3600, 86399, 86400, 172800};
        var expected = new String[]{"たった今", "たった今", "1分前", "59分前", "1時間前", "23時間前", "1日前", "2日前"};
        for (var i = 0; i < seconds.length; i++) {
            var time = EventTime.relative(now.minusSeconds(seconds[i]), now, timezone);
            Assertions.assertEquals(expected[i], plain(GlobalTranslator.render(time, Locale.JAPANESE)));
            Assertions.assertNotNull(time.hoverEvent());
        }
        Assertions.assertEquals("5分後", plain(GlobalTranslator.render(
            EventTime.relative(now.plusSeconds(300), now, timezone), Locale.JAPANESE)));
        Assertions.assertEquals("5 minutes ago", plain(GlobalTranslator.render(
            EventTime.relative(now.minusSeconds(300), now, timezone), Locale.ENGLISH)));
        Assertions.assertEquals("2026-10-25 09:00:00.000 +09:00 [Asia/Tokyo]", plain(EventTime.absolute(now, timezone)));
        // The same occurrence changes its label as the render-time clock advances.
        Assertions.assertEquals(EventPresentationMessages.NOW.asComponent().hoverEvent(
            net.kyori.adventure.text.event.HoverEvent.showText(EventTime.absolute(now, timezone))),
            EventTime.relative(now, now.plusSeconds(59), timezone));
        Assertions.assertEquals("1分前", plain(GlobalTranslator.render(
            EventTime.relative(now, now.plusSeconds(60), timezone), Locale.JAPANESE)));
        Assertions.assertTrue(plain(EventTime.absolute(Instant.parse("2026-10-25T01:30:00Z"),
            ZoneId.of("Europe/London"))).contains("Z [Europe/London]"));
    }

    private static String plain(Component value) {
        var result = new StringBuilder(value instanceof TextComponent text ? text.content() : "");
        value.children().forEach(child -> result.append(plain(child)));
        return result.toString();
    }
}
