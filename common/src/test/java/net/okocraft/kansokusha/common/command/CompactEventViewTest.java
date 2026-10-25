package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.translation.GlobalTranslator;
import net.okocraft.kansokusha.common.language.LanguageProvider;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class CompactEventViewTest {

    @AfterEach
    void unload() {
        LanguageProvider.unload();
    }

    @Test
    void testScopeFollowsConstraintsNotResults() {
        var clock = Clock.systemUTC();
        var single = SearchDisplayScope.of(SearchQueryParser.parse("position minecraft:overworld 1 64 2", clock, ZoneOffset.UTC, null));
        Assertions.assertTrue(single.position().isPresent());
        Assertions.assertEquals(Optional.of(Key.key("minecraft:overworld")), single.world());
        var area = SearchDisplayScope.of(SearchQueryParser.parse("world minecraft:overworld", clock, ZoneOffset.UTC, null));
        Assertions.assertTrue(area.position().isEmpty());
        Assertions.assertTrue(area.world().isPresent());
        var all = SearchDisplayScope.of(SearchQueryParser.parse("", clock, ZoneOffset.UTC, null));
        Assertions.assertTrue(all.position().isEmpty());
        Assertions.assertTrue(all.world().isEmpty());
    }

    @Test
    void testArchivedSectionIsDeferredPagedAndMetadataIsHidden(@TempDir Path dir) throws Exception {
        LanguageProvider.load(dir, List.of(EventPresentationMessages.defaultMessages(), EventCommandMessages.defaultMessages(), SearchCommandMessages.defaultMessages()));
        var calls = new AtomicInteger();
        var event = new SearchPage.Event(UUID.randomUUID(), Key.key("custom:event"), Instant.now(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var detail = new EventDetail(event, PayloadGeneration.FIRST, Instant.now().plusSeconds(60));
        var renderer = new EventRenderer(Clock.systemUTC(), ZoneOffset.UTC, (value, detailed, context) -> Optional.of(
            new EventPresenter.Content(Component.text("Summary"), List.of(), Component.text("Context"), Map.of("text",
                new EventPresenter.Section(EventPresentationMessages.FULL.asComponent(), () -> {
                    calls.incrementAndGet();
                    return java.util.stream.IntStream.range(0, 10).mapToObj(i -> (Component) Component.text("Line " + i)).toList();
                })))), (key, kind) -> Component.text(key.asString()));
        var overview = renderer.detail(detail);
        Assertions.assertEquals(0, calls.get());
        Assertions.assertFalse(overview.stream().map(CompactEventViewTest::plain).anyMatch(text -> text.contains(event.eventId().toString())));
        Assertions.assertEquals(9, renderer.view(detail, "text", 1).size());
        var second = renderer.view(detail, "text", 2);
        Assertions.assertEquals("Line 8", plain(second.getFirst()));
        Assertions.assertEquals(3, second.size());
        Assertions.assertEquals(List.of(EventPresentationMessages.INVALID_VIEW.asComponent()), renderer.view(detail, "text", Integer.MAX_VALUE));
    }

    private static String plain(Component component) {
        var value = GlobalTranslator.render(component, Locale.ENGLISH);
        var text = new StringBuilder(value instanceof TextComponent part ? part.content() : "");
        value.children().forEach(child -> text.append(plain(child)));
        return text.toString();
    }
}
