package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.common.search.query.SearchQueryMessages;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class SearchCommandSupportTest {

    private static final Instant NOW = Instant.parse("2026-09-27T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Key BREAK = Key.key("kansokusha", "block_break");
    private static final Key CHAT = Key.key("kansokusha", "paper_chat");
    private static final Key CUSTOM = Key.key("example", "custom");
    private static final Key OVERWORLD = Key.key("minecraft", "overworld");
    private static final Key NETHER = Key.key("minecraft", "the_nether");
    private static final Key STONE = Key.key("minecraft", "stone");
    private static final Key DIRT = Key.key("minecraft", "dirt");
    private static final UUID PLAYER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174301");

    private final FakeBackend backend = new FakeBackend();
    private final List<Component> messages = new ArrayList<>();
    private final List<String> checkedPermissions = new ArrayList<>();

    @Test
    void testQueryConstraintsAndDefaultLimitArePassedToBackend() {
        this.backend.metadata = metadata(BREAK, CHAT, CUSTOM);

        Assertions.assertTrue(this.execute(false, null, "user Alice action block_break order oldest limit 7", BREAK, CUSTOM));

        var request = this.backend.lastRequest();
        Assertions.assertEquals(Set.of("Alice"), request.query().conditions().users());
        Assertions.assertEquals(Set.of(BREAK), request.query().conditions().actions());
        Assertions.assertEquals(SearchQuery.Order.OLDEST, request.query().order());
        Assertions.assertEquals(7, request.limit());
        Assertions.assertEquals(SearchCommandSupport.NON_PLAYER_DEFAULT_LIMIT, request.defaultLimit());
        Assertions.assertEquals(Set.of(BREAK, CUSTOM), request.constraints().allowedEventTypes());
        // Only exact nodes are asked; wildcard handling is left to the platform permission API.
        Assertions.assertTrue(this.checkedPermissions.stream().noneMatch(permission -> permission.contains("*")));
    }

    @Test
    void testPlayerAndNonPlayerLimits() {
        this.backend.metadata = metadata(BREAK);

        Assertions.assertTrue(this.execute(true, null, "limit 50"));
        Assertions.assertEquals(SearchCommandSupport.PLAYER_DEFAULT_LIMIT, this.backend.lastRequest().defaultLimit());
        Assertions.assertFalse(this.execute(true, null, "limit 51"));
        Assertions.assertEquals(SearchCommandMessages.LIMIT_RANGE.apply(50), this.messages.getLast());

        Assertions.assertTrue(this.execute(false, null, "limit 1000"));
        Assertions.assertFalse(this.execute(false, null, "limit 1001"));
        Assertions.assertEquals(SearchCommandMessages.LIMIT_RANGE.apply(1000), this.messages.getLast());
    }

    @Test
    void testRadiusUsesOriginAndIsRejectedWithoutIt() {
        this.backend.metadata = metadata(BREAK);

        Assertions.assertTrue(this.execute(true, new SearchQuery.Position(OVERWORLD, 10, 64, -4), "radius 5"));
        Assertions.assertEquals(
            Set.of(new SearchQuery.Around(OVERWORLD, 10, -4, 5)),
            this.backend.lastRequest().query().conditions().around()
        );

        this.backend.requests.clear();
        this.messages.clear();
        Assertions.assertFalse(this.execute(false, null, "radius 5"));
        Assertions.assertEquals(
            List.of(SearchCommandMessages.PARSE_ERROR.apply(SearchQueryMessages.RADIUS_UNAVAILABLE.asComponent())),
            this.messages
        );
        Assertions.assertTrue(this.backend.requests.isEmpty());
    }

    @Test
    void testInvalidQueryAndUnauthorizedActionAreRejectedBeforeSearch() {
        this.backend.metadata = metadata(BREAK, CHAT);

        Assertions.assertFalse(this.execute(false, null, "order sideways", BREAK));
        Assertions.assertFalse(this.execute(false, null, "action block_break"));

        Assertions.assertEquals(
            List.of(
                SearchCommandMessages.PARSE_ERROR.apply(SearchQueryMessages.INVALID_ORDER.apply("sideways")),
                SearchCommandMessages.EVENT_PERMISSION.apply("block_break")
            ),
            this.messages
        );
        Assertions.assertTrue(this.backend.requests.isEmpty());
    }

    @Test
    void testResultRenderingAndEventLinkPermission() {
        this.backend.metadata = metadata(BREAK);
        this.backend.page = new SearchPage(List.of(event()), Optional.empty(), Optional.empty());

        Assertions.assertTrue(this.execute(false, null, "", BREAK));
        Assertions.assertTrue(this.execute(false, null, "", BREAK, EventCommandSupport.PERMISSION));

        var actor = Component.text("Alice").hoverEvent(HoverEvent.showText(Component.text(PLAYER_ID.toString())));
        var line = Component.empty()
            .append(Component.text(NOW.toString()))
            .append(Component.text(" | "))
            .append(Component.text("block_break"))
            .append(Component.text(" | "))
            .append(actor.append(Component.text(" -> ")).append(Component.text("minecraft:stone")))
            .append(Component.text(" | "))
            .append(Component.text("@ minecraft:overworld 1 64 -2"))
            .append(Component.text(" | "))
            .append(Component.text("\"/say hello world\""))
            .hoverEvent(HoverEvent.showText(Component.text(event().eventId().toString())));
        Assertions.assertEquals(
            List.of(
                SearchCommandMessages.RESULT.apply(line),
                SearchCommandMessages.RESULT.apply(
                    line.hoverEvent(HoverEvent.showText(SearchCommandMessages.VIEW_EVENT.apply(event().eventId().toString())))
                        .clickEvent(ClickEvent.runCommand("/kansokusha event " + event().eventId()))
                )
            ),
            this.messages
        );
    }

    @Test
    void testNoResultAndBackendFailureMessages() {
        this.backend.metadata = metadata(BREAK);
        Assertions.assertTrue(this.execute(false, null, "", BREAK));

        this.backend.failure = new IllegalStateException("storage failed");
        Assertions.assertTrue(this.execute(false, null, "", BREAK));

        Assertions.assertEquals(
            List.of(SearchCommandMessages.NO_RESULTS.asComponent(), SearchCommandMessages.SEARCH_FAILED.asComponent()),
            this.messages
        );
    }

    @Test
    void testPaginationLinksRoundTripTheCursor() {
        var next = new SearchRequest.Cursor(
            NOW.plusSeconds(5),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174303"),
            SearchRequest.Direction.NEXT
        );
        this.backend.metadata = metadata(BREAK);
        this.backend.page = new SearchPage(List.of(), Optional.of(next), Optional.empty());

        Assertions.assertTrue(this.execute(false, null, "action block_break limit 1", BREAK));

        var command = "/kansokusha search action block_break limit 1 __cursor=next,"
            + next.occurredAt().toEpochMilli() + "," + next.eventId();
        Assertions.assertEquals(
            Component.empty().append(SearchCommandMessages.NEXT.asComponent().clickEvent(ClickEvent.runCommand(command))),
            this.messages.getLast()
        );

        Assertions.assertTrue(this.execute(false, null, command.substring("/kansokusha search ".length()), BREAK));
        Assertions.assertEquals(Optional.of(next), this.backend.lastRequest().cursor());
        Assertions.assertEquals(Set.of(BREAK), this.backend.lastRequest().query().conditions().actions());

        // Quoted text that looks like a cursor stays part of the query.
        Assertions.assertTrue(this.execute(false, null, "filter \"foo __cursor=bar\"", BREAK));
        Assertions.assertEquals(Set.of("foo __cursor=bar"), this.backend.lastRequest().query().conditions().filters());
        Assertions.assertTrue(this.backend.lastRequest().cursor().isEmpty());
    }

    @Test
    void testCompletionUsesHistoricalDataVisibleToTheSender() {
        this.backend.playerNames = List.of("Alice", "Bob");
        this.backend.metadata = new SearchMetadata(Map.of(
            BREAK, new SearchMetadata.EventValues(Set.of(OVERWORLD), Set.of(), Set.of(STONE)),
            CHAT, new SearchMetadata.EventValues(Set.of(NETHER), Set.of(), Set.of(DIRT)),
            CUSTOM, SearchMetadata.EventValues.empty()
        ));

        Assertions.assertEquals(List.of("Bob"), this.suggest(false, "user b"));
        Assertions.assertEquals(List.of("block_break", "example:custom"), this.suggest(false, "action "));
        Assertions.assertEquals(List.of("kansokusha:block_break"), this.suggest(false, "action kansokusha:"));
        Assertions.assertEquals(List.of("minecraft:overworld"), this.suggest(false, "world "));
        Assertions.assertEquals(List.of("minecraft:stone"), this.suggest(false, "target "));
        Assertions.assertFalse(this.suggest(false, "").contains("radius"));
        Assertions.assertTrue(this.suggest(true, "").contains("radius"));

        var suggestions = SearchCommandSupport.suggest(
            this.backend, permission -> true, false, "user Alice action blo", CLOCK, ZoneOffset.UTC
        ).join();
        Assertions.assertEquals(18, suggestions.replacementStart());
    }

    private boolean execute(boolean player, SearchQuery.@Nullable Position origin, String input, Key... permittedEventTypes) {
        var granted = new ArrayList<String>();
        for (var eventType : permittedEventTypes) {
            granted.add(SearchCommandSupport.eventPermission(eventType));
        }
        return this.execute(player, origin, input, granted);
    }

    private boolean execute(boolean player, SearchQuery.@Nullable Position origin, String input, Key eventType, String permission) {
        return this.execute(player, origin, input, List.of(SearchCommandSupport.eventPermission(eventType), permission));
    }

    private boolean execute(boolean player, SearchQuery.@Nullable Position origin, String input, List<String> granted) {
        var source = new SearchCommandSupport.SearchSource(
            permission -> {
                this.checkedPermissions.add(permission);
                return granted.contains(permission);
            },
            this.messages::add,
            player,
            origin
        );
        return SearchCommandSupport.execute(this.backend, source, input, CLOCK, ZoneOffset.UTC);
    }

    private List<String> suggest(boolean radiusAvailable, String input) {
        return SearchCommandSupport.suggest(
            this.backend,
            permission -> !permission.equals(SearchCommandSupport.eventPermission(CHAT)),
            radiusAvailable,
            input,
            CLOCK,
            ZoneOffset.UTC
        ).join().values();
    }

    private static SearchMetadata metadata(Key... eventTypes) {
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
            Optional.of(new BlockPosition(1, 64, -2)),
            Optional.of(new PlayerActor(PLAYER_ID)),
            Optional.of("Alice"),
            Optional.of(STONE),
            Optional.of("/say hello\nworld")
        );
    }

    private static final class FakeBackend implements EventSearchBackend {

        private final List<SearchRequest> requests = new ArrayList<>();
        private SearchMetadata metadata = SearchMetadata.empty();
        private List<String> playerNames = List.of();
        private SearchPage page = new SearchPage(List.of(), Optional.empty(), Optional.empty());
        private @Nullable Throwable failure;

        private SearchRequest lastRequest() {
            return this.requests.getLast();
        }

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
            this.requests.add(request);
            return this.failure != null
                ? CompletableFuture.failedFuture(this.failure)
                : CompletableFuture.completedFuture(this.page);
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
