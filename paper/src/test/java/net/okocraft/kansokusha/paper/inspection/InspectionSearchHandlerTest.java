package net.okocraft.kansokusha.paper.inspection;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.paper.testsupport.TestSources;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class InspectionSearchHandlerTest {

    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174401");
    private static final UUID EVENT_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174402");
    private static final Key BREAK = Key.key("kansokusha", "block_break");
    private static final Key CHAT = Key.key("kansokusha", "paper_chat");
    private static final Key WORLD = Key.key("minecraft", "overworld");
    private static final SearchQuery.Position TARGET = new SearchQuery.Position(WORLD, 123, 64, -456);
    private static final Component FULL_HISTORY = InspectionSearchMessages.VIEW_FULL.asComponent().clickEvent(
        ClickEvent.runCommand("/kansokusha search position minecraft:overworld 123 64 -456")
    );

    @Test
    void testPermissionFilteredRequestAndClickableResult() {
        var backend = new RecordingBackend();
        backend.metadata = metadata(BREAK, CHAT);
        var event = event(BREAK);
        backend.page = new SearchPage(List.of(event), Optional.empty(), Optional.empty());
        var fixture = fixture(backend);
        TestSources.grant(
            fixture.player(),
            SearchCommandSupport.eventPermission(BREAK),
            EventCommandSupport.PERMISSION
        );

        fixture.handler().inspect(PLAYER_ID, TARGET);

        var request = java.util.Objects.requireNonNull(backend.lastRequest);
        Assertions.assertEquals(Set.of(BREAK), request.constraints().allowedEventTypes());
        Assertions.assertEquals(SearchQuery.Conditions.position(TARGET), request.query().conditions());
        Assertions.assertTrue(request.query().exclusions().isEmpty());
        Assertions.assertEquals(SearchQuery.Order.NEWEST, request.query().order());
        Assertions.assertEquals(SearchCommandSupport.PLAYER_DEFAULT_LIMIT, request.limit());
        Assertions.assertTrue(request.cursor().isEmpty());
        Mockito.verify(fixture.player()).sendMessage(
            InspectionSearchMessages.HISTORY.apply("minecraft:overworld 123 64 -456")
        );
        Mockito.verify(fixture.player()).sendMessage(
            SearchCommandMessages.RESULT.apply(
                SearchCommandSupport.formatEvent(event, true)
            )
        );
    }

    @Test
    void testNoHistoryAndBackendFailureMessages() {
        var noHistoryBackend = new RecordingBackend();
        noHistoryBackend.metadata = metadata(BREAK);
        var noHistory = fixture(noHistoryBackend);
        TestSources.grant(
            noHistory.player(),
            SearchCommandSupport.eventPermission(BREAK)
        );

        noHistory.handler().inspect(PLAYER_ID, TARGET);

        Mockito.verify(noHistory.player()).sendMessage(
            InspectionSearchMessages.NO_HISTORY.apply("minecraft:overworld 123 64 -456")
        );

        Mockito.verify(noHistory.player(), Mockito.never()).sendMessage(
            InspectionSearchMessages.HISTORY.apply("minecraft:overworld 123 64 -456")
        );

        var failedBackend = new RecordingBackend();
        failedBackend.metadata = metadata(BREAK);
        failedBackend.failure = new IllegalStateException("storage failed");
        var failed = fixture(failedBackend);
        TestSources.grant(
            failed.player(),
            SearchCommandSupport.eventPermission(BREAK)
        );

        failed.handler().inspect(PLAYER_ID, TARGET);

        Mockito.verify(failed.player()).sendMessage(
            SearchCommandMessages.SEARCH_FAILED.asComponent()
        );
    }

    @Test
    void testFullHistoryLinkRequiresSearchPermissionAndOverflow() {
        var cursor = new SearchRequest.Cursor(
            Instant.parse("2026-09-27T00:00:00Z"),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174403"),
            SearchRequest.Direction.NEXT
        );

        var permittedBackend = new RecordingBackend();
        permittedBackend.metadata = metadata(BREAK);
        permittedBackend.page = new SearchPage(
            List.of(event(BREAK)),
            Optional.of(cursor),
            Optional.empty()
        );
        var permitted = fixture(permittedBackend);
        TestSources.grant(
            permitted.player(),
            SearchCommandSupport.eventPermission(BREAK),
            SearchCommandSupport.PERMISSION
        );

        permitted.handler().inspect(PLAYER_ID, TARGET);

        Mockito.verify(permitted.player()).sendMessage(
            FULL_HISTORY
        );

        var inspectOnlyBackend = new RecordingBackend();
        inspectOnlyBackend.metadata = metadata(BREAK);
        inspectOnlyBackend.page = permittedBackend.page;
        var inspectOnly = fixture(inspectOnlyBackend);
        TestSources.grant(
            inspectOnly.player(),
            SearchCommandSupport.eventPermission(BREAK)
        );

        inspectOnly.handler().inspect(PLAYER_ID, TARGET);

        Mockito.verify(inspectOnly.player(), Mockito.never()).sendMessage(
            FULL_HISTORY
        );
    }

    private static Fixture fixture(RecordingBackend backend) {
        var server = Mockito.mock(Server.class);
        var player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(PLAYER_ID);
        Mockito.when(server.getPlayer(PLAYER_ID)).thenReturn(player);
        return new Fixture(player, new InspectionSearchHandler(server, backend));
    }

    private static SearchMetadata metadata(Key... eventTypes) {
        var events = new LinkedHashMap<Key, SearchMetadata.EventValues>();
        for (var eventType : eventTypes) {
            events.put(eventType, SearchMetadata.EventValues.empty());
        }
        return new SearchMetadata(events);
    }

    private static SearchPage.Event event(Key eventType) {
        return new SearchPage.Event(
            EVENT_ID,
            eventType,
            Instant.parse("2026-09-27T00:00:00Z"),
            Optional.empty(),
            Optional.of(WORLD),
            Optional.of(new BlockPosition(123, 64, -456)),
            Optional.of(new PlayerActor(PLAYER_ID)),
            Optional.of("Alice"),
            Optional.of(Key.key("minecraft", "stone")),
            Optional.empty()
        );
    }

    private record Fixture(Player player, InspectionSearchHandler handler) {
    }

    private static final class RecordingBackend implements EventSearchBackend {

        private SearchMetadata metadata = SearchMetadata.empty();
        private SearchPage page =
            new SearchPage(List.of(), Optional.empty(), Optional.empty());
        private Throwable failure;
        private SearchRequest lastRequest;

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
        public boolean submitPlayerLogin(EventSubmission submission, String username) {
            return true;
        }

        @Override
        public CompletableFuture<Optional<EventDetail>> findEvent(UUID eventId) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<String>> offlinePlayerNames() {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
