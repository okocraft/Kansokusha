package net.okocraft.kansokusha.velocity.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.velocity.testsupport.CommandTester;
import net.okocraft.kansokusha.velocity.testsupport.TestSources;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class SearchCommandTest {

    private static final Instant NOW = Instant.parse("2026-09-27T08:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Key CHAT = Key.key("kansokusha", "velocity_chat");
    private static final Key COMMAND = Key.key("kansokusha", "velocity_command");
    private static final Key CUSTOM = Key.key("example", "custom");
    private static final Key LOBBY = Key.key("kansokusha", "velocity-server/lobby");
    private static final Key SECRET = Key.key("example", "secret_world");
    private static final Key PLAYER_TYPE = Key.key("minecraft", "player");
    private static final Key CREEPER = Key.key("minecraft", "creeper");
    private static final Key MESSAGE = Key.key("kansokusha", "message");
    private static final Key SECRET_TARGET = Key.key("example", "secret_target");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174401");

    private final SearchApi api = new SearchApi();
    private final CommandTester tester = CommandTester.of(
        SearchCommand.createSearchCommand(this.api, CLOCK, ZoneOffset.UTC)
    );

    @Test
    void testSearchPermissionIsRequired() {
        ConsoleCommandSource console = TestSources.console();

        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(console, "search")
        );

        TestSources.deny(console, SearchCommand.PERMISSION);
        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(console, "search")
        );
    }

    @Test
    void testSharedParserAndNonPlayerDefaultsArePassedToBackend() throws Exception {
        this.api.metadata = metadata(Set.of(CHAT));
        ConsoleCommandSource console = console(CHAT);

        Assertions.assertEquals(
            1,
            this.tester.execute(
                console,
                "search user Alice action velocity_chat order oldest limit 7"
            )
        );

        var request = java.util.Objects.requireNonNull(this.api.lastRequest);
        Assertions.assertEquals(Set.of("Alice"), request.query().conditions().users());
        Assertions.assertEquals(Set.of(CHAT), request.query().conditions().actions());
        Assertions.assertEquals(SearchQuery.Order.OLDEST, request.query().order());
        Assertions.assertEquals(7, request.limit());
        Assertions.assertEquals(50, request.defaultLimit());
        Assertions.assertEquals(Set.of(CHAT), request.constraints().allowedEventTypes());
    }

    @Test
    void testConfiguredTimeZoneIsUsedForDateExpressions() throws Exception {
        this.api.metadata = metadata(Set.of(CHAT));
        ConsoleCommandSource console = console(CHAT);
        var tokyoTester = CommandTester.of(
            SearchCommand.createSearchCommand(this.api, CLOCK, ZoneId.of("Asia/Tokyo"))
        );

        Assertions.assertEquals(
            1,
            tokyoTester.execute(console, "search time today")
        );

        var range = java.util.Objects.requireNonNull(this.api.lastRequest)
            .query()
            .conditions()
            .timeRanges()
            .iterator()
            .next();
        Assertions.assertEquals(
            Optional.of(Instant.parse("2026-09-26T15:00:00Z")),
            range.fromInclusive()
        );
        Assertions.assertEquals(
            Optional.of(Instant.parse("2026-09-27T15:00:00Z")),
            range.toExclusive()
        );
    }

    @Test
    void testExplicitUnauthorizedActionIsRejectedWithExactPermissionNode() throws Exception {
        this.api.metadata = metadata(Set.of(CHAT, COMMAND));
        ConsoleCommandSource console = console();
        TestSources.deny(console, eventPermission(CHAT));

        Assertions.assertEquals(
            0,
            this.tester.execute(console, "search action velocity_chat")
        );

        Mockito.verify(console).hasPermission(eventPermission(CHAT));
        Mockito.verify(console).sendMessage(
            SearchCommandMessages.EVENT_PERMISSION.apply("velocity_chat")
        );
        Assertions.assertNull(this.api.lastRequest);
    }

    @Test
    void testCompletionMatchesPaperAndHidesUnauthorizedEventMetadata() {
        this.api.playerNames = List.of("Alice", "Bob");
        this.api.metadata = new SearchMetadata(Map.of(
            CHAT,
            new SearchMetadata.EventValues(
                Set.of(LOBBY),
                Set.of(PLAYER_TYPE),
                Set.of(MESSAGE)
            ),
            COMMAND,
            new SearchMetadata.EventValues(
                Set.of(SECRET),
                Set.of(CREEPER),
                Set.of(SECRET_TARGET)
            ),
            CUSTOM,
            SearchMetadata.EventValues.empty()
        ));

        ConsoleCommandSource console = console(CHAT, CUSTOM);
        TestSources.deny(console, eventPermission(COMMAND));

        Assertions.assertEquals(
            List.of("Alice", "Bob"),
            this.tester.suggest(console, "search user ")
        );
        Assertions.assertEquals(
            List.of("example:custom", "velocity_chat"),
            this.tester.suggest(console, "search action ")
        );
        Assertions.assertEquals(
            List.of("kansokusha:velocity_chat"),
            this.tester.suggest(console, "search action kansokusha:")
        );
        Assertions.assertEquals(
            List.of(LOBBY.asString()),
            this.tester.suggest(console, "search world ")
        );
        Assertions.assertEquals(
            List.of(PLAYER_TYPE.asString()),
            this.tester.suggest(console, "search actor-type ")
        );
        Assertions.assertEquals(
            List.of(MESSAGE.asString()),
            this.tester.suggest(console, "search target ")
        );
        Assertions.assertEquals(
            List.of(MESSAGE.asString()),
            this.tester.suggest(console, "search include ")
        );
        Assertions.assertEquals(
            List.of("block", "entity", "player"),
            this.tester.suggest(console, "search actor-kind ")
        );
        Assertions.assertEquals(
            List.of("newest", "oldest"),
            this.tester.suggest(console, "search order ")
        );
        Assertions.assertTrue(
            this.tester.suggest(console, "search exclude ").contains("actor-kind")
        );
        Assertions.assertFalse(this.tester.suggest(console, "search ").contains("radius"));
        Assertions.assertFalse(
            this.tester.suggest(console, "search action ").contains("velocity_command")
        );
        Assertions.assertFalse(
            this.tester.suggest(console, "search world ").contains(SECRET.asString())
        );
        Assertions.assertFalse(
            this.tester.suggest(console, "search actor-type ").contains(CREEPER.asString())
        );
        Assertions.assertFalse(
            this.tester.suggest(console, "search target ").contains(SECRET_TARGET.asString())
        );
    }

    @Test
    void testRadiusIsRejectedForPlayerAndConsoleButExplicitPositionsAreAllowed()
        throws Exception {
        this.api.metadata = metadata(Set.of(CHAT));

        Player player = player(CHAT);
        Assertions.assertEquals(0, this.tester.execute(player, "search radius 5"));
        Mockito.verify(player).sendMessage(SearchCommandMessages.PARSE_ERROR.apply("radius requires the position of a player"));
        Assertions.assertNull(this.api.lastRequest);

        ConsoleCommandSource console = console(CHAT);
        Assertions.assertEquals(0, this.tester.execute(console, "search radius 5"));
        Mockito.verify(console).sendMessage(SearchCommandMessages.PARSE_ERROR.apply("radius requires the position of a player"));
        Assertions.assertNull(this.api.lastRequest);

        Assertions.assertEquals(
            1,
            this.tester.execute(
                console,
                "search around example:world 10 20 5 position example:world 1 2 3"
            )
        );
        var request = java.util.Objects.requireNonNull(this.api.lastRequest);
        Assertions.assertEquals(
            Set.of(new SearchQuery.Around(Key.key("example", "world"), 10, 20, 5)),
            request.query().conditions().around()
        );
        Assertions.assertEquals(
            Set.of(new SearchQuery.Position(Key.key("example", "world"), 1, 2, 3)),
            request.query().conditions().positions()
        );
    }

    @Test
    void testPlayerAndNonPlayerLimits() throws Exception {
        this.api.metadata = metadata(Set.of(CHAT));

        Player player = player(CHAT);
        Assertions.assertEquals(1, this.tester.execute(player, "search limit 50"));
        Assertions.assertEquals(
            10,
            java.util.Objects.requireNonNull(this.api.lastRequest).defaultLimit()
        );

        this.api.lastRequest = null;
        Assertions.assertEquals(0, this.tester.execute(player, "search limit 51"));
        Mockito.verify(player).sendMessage(SearchCommandMessages.LIMIT_RANGE.apply("50"));
        Assertions.assertNull(this.api.lastRequest);

        ConsoleCommandSource console = console(CHAT);
        Assertions.assertEquals(1, this.tester.execute(console, "search limit 1000"));
        Assertions.assertEquals(
            50,
            java.util.Objects.requireNonNull(this.api.lastRequest).defaultLimit()
        );

        this.api.lastRequest = null;
        Assertions.assertEquals(0, this.tester.execute(console, "search limit 1001"));
        Mockito.verify(console).sendMessage(SearchCommandMessages.LIMIT_RANGE.apply("1000"));
        Assertions.assertNull(this.api.lastRequest);
    }

    @Test
    void testRenderingAndClickablePaginationUseSharedAdventureSupport() throws Exception {
        var event = event();
        var previous = new SearchRequest.Cursor(
            NOW.minusSeconds(5),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174402"),
            SearchRequest.Direction.PREVIOUS
        );
        var next = new SearchRequest.Cursor(
            NOW.plusSeconds(5),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174403"),
            SearchRequest.Direction.NEXT
        );

        this.api.metadata = metadata(Set.of(CHAT));
        this.api.page = new SearchPage(
            List.of(event),
            Optional.of(next),
            Optional.of(previous)
        );
        ConsoleCommandSource console = console(CHAT);
        TestSources.grant(console, EventCommand.PERMISSION);

        Assertions.assertEquals(
            1,
            this.tester.execute(
                console,
                "search action velocity_chat limit 1"
            )
        );

        var actor = Component.text("Alice").hoverEvent(
            HoverEvent.showText(Component.text(PLAYER_ID.toString()))
        );
        var expectedLine = Component.empty()
            .append(Component.text(NOW.toString()))
            .append(Component.text(" | "))
            .append(Component.text("velocity_chat"))
            .append(Component.text(" | "))
            .append(actor.append(Component.text(" -> ")).append(Component.text(MESSAGE.asString())))
            .append(Component.text(" | "))
            .append(Component.text("@ " + LOBBY.asString()))
            .append(Component.text(" | "))
            .append(Component.text("\"hello world\""))
            .clickEvent(ClickEvent.runCommand("/kansokusha event " + event.eventId()))
            .hoverEvent(HoverEvent.showText(Component.text(event.eventId().toString())));

        Mockito.verify(console).sendMessage(SearchCommandMessages.RESULT.apply(expectedLine));

        var expectedPagination = Component.empty()
            .append(SearchCommandMessages.PREVIOUS.asComponent().clickEvent(
                ClickEvent.runCommand(
                    "/kansokusha search action velocity_chat limit 1 __cursor=previous,"
                        + previous.occurredAt().toEpochMilli()
                        + ","
                        + previous.eventId()
                )
            ))
            .append(Component.text(" | "))
            .append(SearchCommandMessages.NEXT.asComponent().clickEvent(
                ClickEvent.runCommand(
                    "/kansokusha search action velocity_chat limit 1 __cursor=next,"
                        + next.occurredAt().toEpochMilli()
                        + ","
                        + next.eventId()
                )
            ));
        Mockito.verify(console).sendMessage(expectedPagination);
    }

    @Test
    void testResultIsNotClickableWithoutEventCommandPermission() throws Exception {
        var event = event();
        this.api.metadata = metadata(Set.of(CHAT));
        this.api.page = new SearchPage(List.of(event), Optional.empty(), Optional.empty());
        ConsoleCommandSource console = console(CHAT);

        Assertions.assertEquals(
            1,
            this.tester.execute(console, "search action velocity_chat")
        );

        Mockito.verify(console).sendMessage(
            SearchCommandMessages.RESULT.apply(
                SearchCommandSupport.formatEvent(event, false)
            )
        );
        Mockito.verify(console).hasPermission(EventCommand.PERMISSION);
    }

    @Test
    void testPaginationCursorAndQuotedCursorTextAreParsedBySharedSupport() throws Exception {
        this.api.metadata = metadata(Set.of(CHAT));
        ConsoleCommandSource console = console(CHAT);
        var next = new SearchRequest.Cursor(
            NOW.plusSeconds(5),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174404"),
            SearchRequest.Direction.NEXT
        );

        Assertions.assertEquals(
            1,
            this.tester.execute(
                console,
                "search action velocity_chat __cursor=next,"
                    + next.occurredAt().toEpochMilli()
                    + ","
                    + next.eventId()
            )
        );
        Assertions.assertEquals(
            Optional.of(next),
            java.util.Objects.requireNonNull(this.api.lastRequest).cursor()
        );

        this.api.lastRequest = null;
        Assertions.assertEquals(
            1,
            this.tester.execute(console, "search filter \"foo __cursor=bar\"")
        );
        var quoted = java.util.Objects.requireNonNull(this.api.lastRequest);
        Assertions.assertEquals(
            Set.of("foo __cursor=bar"),
            quoted.query().conditions().filters()
        );
        Assertions.assertTrue(quoted.cursor().isEmpty());
    }

    @Test
    void testNoResultParseAndBackendFailureMessages() throws Exception {
        this.api.metadata = metadata(Set.of(CHAT));

        ConsoleCommandSource invalid = console(CHAT);
        Assertions.assertEquals(0, this.tester.execute(invalid, "search order sideways"));
        Mockito.verify(invalid).sendMessage(
            SearchCommandMessages.PARSE_ERROR.apply("order must be newest or oldest: sideways")
        );

        ConsoleCommandSource noResult = console(CHAT);
        Assertions.assertEquals(1, this.tester.execute(noResult, "search"));
        Mockito.verify(noResult).sendMessage(SearchCommandMessages.NO_RESULTS.asComponent());

        this.api.failure = new IllegalStateException("storage failed");
        ConsoleCommandSource failed = console(CHAT);
        Assertions.assertEquals(1, this.tester.execute(failed, "search"));
        Mockito.verify(failed).sendMessage(SearchCommandMessages.SEARCH_FAILED.asComponent());
    }

    @Test
    void testJapaneseBundleContainsEverySearchMessageKey() throws Exception {
        var properties = new Properties();
        try (
            var input = SearchCommandTest.class.getClassLoader()
                .getResourceAsStream("languages/ja.properties")
        ) {
            Assertions.assertNotNull(input);
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }

        Assertions.assertTrue(
            properties.stringPropertyNames().containsAll(
                SearchCommandMessages.DEFINER.getCollectedMessages().keySet()
            )
        );
        Assertions.assertTrue(
            properties.stringPropertyNames().containsAll(
                EventCommandMessages.DEFINER.getCollectedMessages().keySet()
            )
        );
    }

    private ConsoleCommandSource console(Key... permittedEventTypes) {
        ConsoleCommandSource console = TestSources.console();
        TestSources.grant(console, SearchCommand.PERMISSION);
        for (var eventType : permittedEventTypes) {
            TestSources.grant(console, eventPermission(eventType));
        }
        return console;
    }

    private Player player(Key... permittedEventTypes) {
        Player player = TestSources.player();
        TestSources.grant(player, SearchCommand.PERMISSION);
        for (var eventType : permittedEventTypes) {
            TestSources.grant(player, eventPermission(eventType));
        }
        return player;
    }

    private static String eventPermission(Key eventType) {
        return SearchCommandSupport.eventPermission(eventType);
    }

    private static SearchMetadata metadata(Set<Key> eventTypes) {
        var events = new LinkedHashMap<Key, SearchMetadata.EventValues>();
        for (var eventType : eventTypes) {
            events.put(eventType, SearchMetadata.EventValues.empty());
        }
        return new SearchMetadata(events);
    }

    private static SearchPage.Event event() {
        return new SearchPage.Event(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174405"),
            CHAT,
            NOW,
            Optional.empty(),
            Optional.of(LOBBY),
            Optional.empty(),
            Optional.of(new PlayerActor(PLAYER_ID)),
            Optional.of("Alice"),
            Optional.of(MESSAGE),
            Optional.of("hello\nworld")
        );
    }

    private static final class SearchApi implements EventSearchBackend {

        private SearchMetadata metadata = SearchMetadata.empty();
        private List<String> playerNames = List.of();
        private SearchPage page = new SearchPage(List.of(), Optional.empty(), Optional.empty());
        private SearchRequest lastRequest;
        private Throwable failure;

        @Override
        public boolean submitSearchable(EventSubmission submission, String searchText) {
            return true;
        }

        @Override
        public boolean submitPlayerLogin(EventSubmission submission, String username) {
            return true;
        }

        @Override
        public CompletableFuture<SearchPage> search(SearchRequest request) {
            this.lastRequest = request;
            if (this.failure != null) {
                return CompletableFuture.failedFuture(this.failure);
            }
            return CompletableFuture.completedFuture(this.page);
        }

        @Override
        public CompletableFuture<Optional<EventDetail>> findEvent(UUID eventId) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<SearchMetadata> searchMetadata() {
            return CompletableFuture.completedFuture(this.metadata);
        }

        @Override
        public CompletableFuture<List<String>> offlinePlayerNames() {
            return CompletableFuture.completedFuture(this.playerNames);
        }
    }
}
