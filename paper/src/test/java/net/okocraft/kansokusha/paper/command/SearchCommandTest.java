package net.okocraft.kansokusha.paper.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.api.Kansokusha;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.player.PlayerNameDirectory;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.paper.testsupport.CommandTester;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class SearchCommandTest {

    private static final Instant NOW = Instant.parse("2026-09-27T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Key BREAK = Key.key("kansokusha", "block_break");
    private static final Key CHAT = Key.key("kansokusha", "paper_chat");
    private static final Key CUSTOM = Key.key("example", "custom");
    private static final Key OVERWORLD = Key.key("minecraft", "overworld");
    private static final Key NETHER = Key.key("minecraft", "the_nether");
    private static final Key STONE = Key.key("minecraft", "stone");
    private static final Key DIRT = Key.key("minecraft", "dirt");
    private static final Key CREEPER = Key.key("minecraft", "creeper");
    private static final Key ZOMBIE = Key.key("minecraft", "zombie");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174301");

    private final CommandTester tester = CommandTester.of(
        SearchCommand.createSearchCommand(CLOCK, ZoneOffset.UTC).build()
    );

    private SearchApi api;

    @BeforeEach
    void setUp() {
        this.api = new SearchApi();
        Kansokusha.setApi(this.api);
    }

    @AfterEach
    void tearDown() {
        Kansokusha.setApi(null);
    }

    @Test
    void testSearchPermissionIsRequired() {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);

        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(TestSources.ofSenderOnly(console), "search")
        );

        TestSources.deny(console, SearchCommand.PERMISSION);
        Assertions.assertThrows(
            CommandSyntaxException.class,
            () -> this.tester.execute(TestSources.ofSenderOnly(console), "search")
        );
    }

    @Test
    void testParserOutputIsPassedToBackend() throws Exception {
        this.api.metadata = metadata(Set.of(BREAK));
        ConsoleCommandSender console = console(BREAK);

        Assertions.assertEquals(
            1,
            this.tester.execute(
                TestSources.ofSenderOnly(console),
                "search user Alice action block_break order oldest limit 7"
            )
        );

        var request = java.util.Objects.requireNonNull(this.api.lastRequest);
        Assertions.assertEquals(Set.of("Alice"), request.query().conditions().users());
        Assertions.assertEquals(Set.of(BREAK), request.query().conditions().actions());
        Assertions.assertEquals(SearchQuery.Order.OLDEST, request.query().order());
        Assertions.assertEquals(7, request.limit());
        Assertions.assertEquals(50, request.defaultLimit());
        Assertions.assertEquals(Set.of(BREAK), request.constraints().allowedEventTypes());
    }

    @Test
    void testInvalidQueryUsesLocalizedError() throws Exception {
        ConsoleCommandSender console = console();

        Assertions.assertEquals(
            0,
            this.tester.execute(TestSources.ofSenderOnly(console), "search order sideways")
        );
        Mockito.verify(console).sendMessage(SearchCommandMessages.PARSE_ERROR.asComponent());
        Assertions.assertNull(this.api.lastRequest);
    }

    @Test
    void testExplicitUnauthorizedActionIsRejectedWithExactPermissionNode() throws Exception {
        this.api.metadata = metadata(Set.of(BREAK, CHAT));
        ConsoleCommandSender console = console();
        TestSources.deny(console, eventPermission(BREAK));

        Assertions.assertEquals(
            0,
            this.tester.execute(
                TestSources.ofSenderOnly(console),
                "search action block_break"
            )
        );

        Mockito.verify(console).hasPermission(eventPermission(BREAK));
        Mockito.verify(console).sendMessage(
            SearchCommandMessages.EVENT_PERMISSION.apply("block_break")
        );
        Assertions.assertNull(this.api.lastRequest);
    }

    @Test
    void testUnauthorizedHistoricalEventsAreRemovedFromBackendConstraints() throws Exception {
        this.api.metadata = metadata(Set.of(BREAK, CHAT, CUSTOM));
        ConsoleCommandSender console = console(BREAK, CUSTOM);
        TestSources.deny(console, eventPermission(CHAT));

        Assertions.assertEquals(
            1,
            this.tester.execute(TestSources.ofSenderOnly(console), "search")
        );

        Assertions.assertEquals(
            Set.of(BREAK, CUSTOM),
            java.util.Objects.requireNonNull(this.api.lastRequest).constraints().allowedEventTypes()
        );
        Mockito.verify(console).hasPermission(eventPermission(BREAK));
        Mockito.verify(console).hasPermission(eventPermission(CHAT));
        Mockito.verify(console).hasPermission(eventPermission(CUSTOM));
    }

    @Test
    void testContextAwareCompletionUsesHistoricalDataAndPermissions() {
        this.api.playerNames = List.of("Alice", "Bob");
        this.api.metadata = new SearchMetadata(java.util.Map.of(
            BREAK,
            new SearchMetadata.EventValues(
                Set.of(OVERWORLD),
                Set.of(CREEPER),
                Set.of(STONE)
            ),
            CHAT,
            new SearchMetadata.EventValues(
                Set.of(NETHER),
                Set.of(ZOMBIE),
                Set.of(DIRT)
            ),
            CUSTOM,
            SearchMetadata.EventValues.empty()
        ));
        ConsoleCommandSender console = console(BREAK, CUSTOM);
        TestSources.deny(console, eventPermission(CHAT));
        var source = TestSources.ofSenderOnly(console);

        Assertions.assertEquals(
            List.of("Alice", "Bob"),
            this.tester.suggest(source, "search user ")
        );
        Assertions.assertEquals(
            List.of("block_break", "example:custom"),
            this.tester.suggest(source, "search action ")
        );
        Assertions.assertEquals(
            List.of("kansokusha:block_break"),
            this.tester.suggest(source, "search action kansokusha:")
        );
        Assertions.assertEquals(
            List.of("minecraft:overworld"),
            this.tester.suggest(source, "search world ")
        );
        Assertions.assertEquals(
            List.of("minecraft:creeper"),
            this.tester.suggest(source, "search actor-type ")
        );
        Assertions.assertEquals(
            List.of("minecraft:stone"),
            this.tester.suggest(source, "search target ")
        );
        Assertions.assertEquals(
            List.of("minecraft:stone"),
            this.tester.suggest(source, "search include ")
        );
        Assertions.assertEquals(
            List.of("block", "entity", "player"),
            this.tester.suggest(source, "search actor-kind ")
        );
        Assertions.assertEquals(
            List.of("newest", "oldest"),
            this.tester.suggest(source, "search order ")
        );
        Assertions.assertTrue(
            this.tester.suggest(source, "search exclude ").contains("actor-kind")
        );
        Assertions.assertFalse(this.tester.suggest(source, "search ").contains("radius"));
        Assertions.assertFalse(
            this.tester.suggest(source, "search action ").contains("paper_chat")
        );
        Assertions.assertFalse(
            this.tester.suggest(source, "search world ").contains("minecraft:the_nether")
        );
        Assertions.assertFalse(
            this.tester.suggest(source, "search actor-type ").contains("minecraft:zombie")
        );
        Assertions.assertFalse(
            this.tester.suggest(source, "search target ").contains("minecraft:dirt")
        );
        Assertions.assertFalse(
            this.tester.suggest(source, "search include ").contains("minecraft:dirt")
        );
    }

    @Test
    void testPlayerRadiusUsesCurrentWorldAndBlockPosition() throws Exception {
        this.api.metadata = metadata(Set.of(BREAK));
        Player player = player(BREAK);
        World world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        Mockito.when(player.getLocation()).thenReturn(new Location(world, 10.8, 64.2, -3.2));

        Assertions.assertEquals(
            1,
            this.tester.execute(TestSources.of(player), "search radius 5")
        );

        var request = java.util.Objects.requireNonNull(this.api.lastRequest);
        Assertions.assertEquals(10, request.defaultLimit());
        Assertions.assertEquals(Set.of(5), request.query().conditions().radii());
        Assertions.assertEquals(
            Optional.of(new SearchRequest.RadiusCenter(OVERWORLD, 10, -4)),
            request.radiusCenter()
        );
    }

    @Test
    void testConsoleRadiusIsRejectedButAroundIsAllowed() throws Exception {
        this.api.metadata = metadata(Set.of(BREAK));
        ConsoleCommandSender console = console(BREAK);
        var source = TestSources.ofSenderOnly(console);

        Assertions.assertEquals(0, this.tester.execute(source, "search radius 5"));
        Mockito.verify(console).sendMessage(
            SearchCommandMessages.RADIUS_PLAYER_ONLY.asComponent()
        );
        Assertions.assertNull(this.api.lastRequest);

        Mockito.clearInvocations(console);
        Assertions.assertEquals(
            1,
            this.tester.execute(
                source,
                "search around minecraft:overworld 10 20 5"
            )
        );
        var request = java.util.Objects.requireNonNull(this.api.lastRequest);
        Assertions.assertTrue(request.radiusCenter().isEmpty());
        Assertions.assertEquals(
            Set.of(new SearchQuery.Around(OVERWORLD, 10, 20, 5)),
            request.query().conditions().around()
        );
    }

    @Test
    void testPlayerAndConsoleLimits() throws Exception {
        this.api.metadata = metadata(Set.of(BREAK));

        Player player = player(BREAK);
        Assertions.assertEquals(
            1,
            this.tester.execute(TestSources.of(player), "search limit 50")
        );
        Assertions.assertEquals(50, java.util.Objects.requireNonNull(this.api.lastRequest).limit());
        Assertions.assertEquals(10, this.api.lastRequest.defaultLimit());

        this.api.lastRequest = null;
        Assertions.assertEquals(
            0,
            this.tester.execute(TestSources.of(player), "search limit 51")
        );
        Mockito.verify(player).sendMessage(SearchCommandMessages.LIMIT_RANGE.apply("50"));
        Assertions.assertNull(this.api.lastRequest);

        ConsoleCommandSender console = console(BREAK);
        Assertions.assertEquals(
            1,
            this.tester.execute(TestSources.ofSenderOnly(console), "search limit 1000")
        );
        Assertions.assertEquals(1000, java.util.Objects.requireNonNull(this.api.lastRequest).limit());
        Assertions.assertEquals(50, this.api.lastRequest.defaultLimit());

        this.api.lastRequest = null;
        Assertions.assertEquals(
            0,
            this.tester.execute(TestSources.ofSenderOnly(console), "search limit 1001")
        );
        Mockito.verify(console).sendMessage(SearchCommandMessages.LIMIT_RANGE.apply("1000"));
        Assertions.assertNull(this.api.lastRequest);
    }

    @Test
    void testResultRenderingIncludesSearchTextAndPlayerNameWithUuidHover() throws Exception {
        var event = event();
        this.api.metadata = metadata(Set.of(BREAK));
        this.api.page = new SearchPage(List.of(event), Optional.empty(), Optional.empty());
        ConsoleCommandSender console = console(BREAK);
        TestSources.grant(console, EventCommand.PERMISSION);

        Assertions.assertEquals(
            1,
            this.tester.execute(
                TestSources.ofSenderOnly(console),
                "search action block_break"
            )
        );

        var actor = Component.text("Alice").hoverEvent(
            HoverEvent.showText(Component.text(PLAYER_ID.toString()))
        );
        var expected = Component.empty()
            .append(Component.text(NOW.toString()))
            .append(Component.text(" | "))
            .append(Component.text("block_break"))
            .append(Component.text(" | "))
            .append(
                actor.append(Component.text(" -> "))
                    .append(Component.text("minecraft:stone"))
            )
            .append(Component.text(" | "))
            .append(Component.text("@ minecraft:overworld 1 64 -2"))
            .append(Component.text(" | "))
            .append(Component.text("\"/say hello world\""))
            .clickEvent(ClickEvent.runCommand("/kansokusha event " + event().eventId()))
            .hoverEvent(HoverEvent.showText(Component.text(event().eventId().toString())));

        Mockito.verify(console).sendMessage(SearchCommandMessages.RESULT.apply(expected));
    }

    @Test
    void testResultIsNotClickableWithoutEventCommandPermission() throws Exception {
        var event = event();
        this.api.metadata = metadata(Set.of(BREAK));
        this.api.page = new SearchPage(List.of(event), Optional.empty(), Optional.empty());
        ConsoleCommandSender console = console(BREAK);

        Assertions.assertEquals(
            1,
            this.tester.execute(
                TestSources.ofSenderOnly(console),
                "search action block_break"
            )
        );

        Mockito.verify(console).sendMessage(
            SearchCommandMessages.RESULT.apply(SearchCommand.formatEvent(event, false))
        );
        Mockito.verify(console).hasPermission(EventCommand.PERMISSION);
    }

    @Test
    void testPaginationComponentsRetainQueryAndCursor() throws Exception {
        var previous = new SearchRequest.Cursor(
            NOW.minusSeconds(5),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174302"),
            SearchRequest.Direction.PREVIOUS
        );
        var next = new SearchRequest.Cursor(
            NOW.plusSeconds(5),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174303"),
            SearchRequest.Direction.NEXT
        );
        var page = new SearchPage(List.of(event()), Optional.of(next), Optional.of(previous));

        var expected = Component.empty()
            .append(SearchCommandMessages.PREVIOUS.asComponent().clickEvent(
                ClickEvent.runCommand(
                    "/kansokusha search action block_break limit 1 __cursor=previous,"
                        + previous.occurredAt().toEpochMilli()
                        + ","
                        + previous.eventId()
                )
            ))
            .append(Component.text(" | "))
            .append(SearchCommandMessages.NEXT.asComponent().clickEvent(
                ClickEvent.runCommand(
                    "/kansokusha search action block_break limit 1 __cursor=next,"
                        + next.occurredAt().toEpochMilli()
                        + ","
                        + next.eventId()
                )
            ));

        Assertions.assertEquals(
            expected,
            SearchCommand.paginationComponent("action block_break limit 1", page)
        );

        this.api.metadata = metadata(Set.of(BREAK));
        ConsoleCommandSender console = console(BREAK);
        Assertions.assertEquals(
            1,
            this.tester.execute(
                TestSources.ofSenderOnly(console),
                "search action block_break limit 1 __cursor=next,"
                    + next.occurredAt().toEpochMilli()
                    + ","
                    + next.eventId()
            )
        );
        Assertions.assertEquals(
            Optional.of(next),
            java.util.Objects.requireNonNull(this.api.lastRequest).cursor()
        );
        Assertions.assertEquals(Set.of(BREAK), this.api.lastRequest.query().conditions().actions());
    }

    @Test
    void testQuotedFilterContainingCursorPrefixIsNotTreatedAsPagination() throws Exception {
        this.api.metadata = metadata(Set.of(BREAK));
        ConsoleCommandSender console = console(BREAK);

        Assertions.assertEquals(
            1,
            this.tester.execute(
                TestSources.ofSenderOnly(console),
                "search filter \"foo __cursor=bar\""
            )
        );

        var request = java.util.Objects.requireNonNull(this.api.lastRequest);
        Assertions.assertEquals(
            Set.of("foo __cursor=bar"),
            request.query().conditions().filters()
        );
        Assertions.assertTrue(request.cursor().isEmpty());
    }

    @Test
    void testNoResultAndBackendFailureMessages() throws Exception {
        this.api.metadata = metadata(Set.of(BREAK));
        ConsoleCommandSender noResult = console(BREAK);

        Assertions.assertEquals(
            1,
            this.tester.execute(TestSources.ofSenderOnly(noResult), "search")
        );
        Mockito.verify(noResult).sendMessage(SearchCommandMessages.NO_RESULTS.asComponent());

        this.api.failure = new IllegalStateException("storage failed");
        ConsoleCommandSender failed = console(BREAK);
        Assertions.assertEquals(
            1,
            this.tester.execute(TestSources.ofSenderOnly(failed), "search")
        );
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

    private ConsoleCommandSender console(Key... permittedEventTypes) {
        ConsoleCommandSender console = Mockito.mock(ConsoleCommandSender.class);
        TestSources.grant(console, SearchCommand.PERMISSION);
        for (var eventType : permittedEventTypes) {
            TestSources.grant(console, eventPermission(eventType));
        }
        return console;
    }

    private Player player(Key... permittedEventTypes) {
        Player player = Mockito.mock(Player.class);
        TestSources.grant(player, SearchCommand.PERMISSION);
        for (var eventType : permittedEventTypes) {
            TestSources.grant(player, eventPermission(eventType));
        }
        return player;
    }

    private static String eventPermission(Key eventType) {
        return SearchCommand.EVENT_PERMISSION_PREFIX + eventType.asString();
    }

    private static SearchMetadata metadata(Set<Key> eventTypes) {
        var events = new java.util.LinkedHashMap<Key, SearchMetadata.EventValues>();
        for (var eventType : eventTypes) {
            events.put(eventType, SearchMetadata.EventValues.empty());
        }
        return new SearchMetadata(events);
    }

    private static SearchPage.Event event() {
        return new SearchPage.Event(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174304"),
            BREAK,
            NOW,
            Optional.empty(),
            Optional.of(OVERWORLD),
            OptionalInt.of(1),
            OptionalInt.of(64),
            OptionalInt.of(-2),
            Optional.of(SearchQuery.ActorKind.PLAYER),
            Optional.of(PLAYER_ID),
            Optional.of("Alice"),
            Optional.empty(),
            Optional.of(STONE),
            Optional.of("/say hello\nworld")
        );
    }

    private static final class SearchApi
        implements KansokushaApi, PlayerNameDirectory, EventSearchBackend {

        private SearchMetadata metadata = SearchMetadata.empty();
        private List<String> playerNames = List.of();
        private SearchPage page = new SearchPage(List.of(), Optional.empty(), Optional.empty());
        private SearchRequest lastRequest;
        private Throwable failure;

        @Override
        public Optional<Key> localServerKey() {
            return Optional.empty();
        }

        @Override
        public void registerEventType(EventTypeDefinition definition) {
        }

        @Override
        public boolean submit(EventSubmission submission) {
            return true;
        }

        @Override
        public boolean submitPlayerLogin(EventSubmission submission, String username) {
            return true;
        }

        @Override
        public CompletableFuture<Optional<UUID>> resolvePlayerName(String name) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<String>> offlinePlayerNames() {
            return CompletableFuture.completedFuture(this.playerNames);
        }

        @Override
        public boolean submitSearchable(EventSubmission submission, String searchText) {
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
        public CompletableFuture<SearchMetadata> searchMetadata() {
            return CompletableFuture.completedFuture(this.metadata);
        }

        @Override
        public CompletableFuture<List<UUID>> findEventIdsContaining(String literal) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
