package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class EventCommandSupportTest {

    private static final UUID EVENT_ID =
        UUID.fromString("0199a123-4567-789a-8bcd-ef0123456789");
    private static final UUID PLAYER_ID =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174501");
    private static final Key BREAK = Key.key("kansokusha", "block_break");
    private static final Key CHAT = Key.key("kansokusha", "paper_chat");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-27T09:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-27T09:00:00Z");

    @Test
    void testInvalidUuidSyntaxDoesNotLookup() {
        var api = new TestApi();
        var messages = new ArrayList<Component>();

        Assertions.assertFalse(
            EventCommandSupport.execute(api, "not-a-uuid", ignored -> true, messages::add)
        );
        Assertions.assertNull(api.lookupId);
        Assertions.assertEquals(List.of(EventCommandMessages.INVALID_ID.asComponent()), messages);
    }

    @Test
    void testUnknownUuidIsNotFound() {
        var api = new TestApi();
        var messages = new ArrayList<Component>();

        Assertions.assertTrue(
            EventCommandSupport.execute(api, EVENT_ID.toString(), ignored -> true, messages::add)
        );
        Assertions.assertEquals(EVENT_ID, api.lookupId);
        Assertions.assertEquals(List.of(EventCommandMessages.NOT_FOUND.asComponent()), messages);
    }

    @Test
    void testEventPermissionUsesOnlyExactPlatformPermissionNode() {
        var api = new TestApi();
        api.lookup = CompletableFuture.completedFuture(Optional.of(minimalEvent(BREAK)));
        var permissionChecks = new ArrayList<String>();
        var messages = new ArrayList<Component>();

        Assertions.assertTrue(EventCommandSupport.execute(
            api,
            EVENT_ID.toString(),
            permission -> {
                permissionChecks.add(permission);
                return false;
            },
            messages::add
        ));

        Assertions.assertEquals(
            List.of("kansokusha.command.search.event.kansokusha:block_break"),
            permissionChecks
        );
        Assertions.assertEquals(
            List.of(EventCommandMessages.PERMISSION_DENIED.asComponent()),
            messages
        );
    }

    @Test
    void testLookupFailureIsNotReportedAsNotFound() {
        var api = new TestApi();
        api.lookup = CompletableFuture.failedFuture(new IllegalStateException("database failed"));
        var messages = new ArrayList<Component>();

        Assertions.assertTrue(
            EventCommandSupport.execute(api, EVENT_ID.toString(), ignored -> true, messages::add)
        );
        Assertions.assertEquals(
            List.of(EventCommandMessages.LOOKUP_FAILED.asComponent()),
            messages
        );
    }

    @Test
    void testLookupDoesNotBlockCallingThread() {
        var api = new TestApi();
        api.lookup = new CompletableFuture<>();
        var messages = new ArrayList<Component>();

        Assertions.assertTrue(
            EventCommandSupport.execute(api, EVENT_ID.toString(), ignored -> true, messages::add)
        );
        Assertions.assertTrue(messages.isEmpty());

        api.lookup.complete(Optional.empty());
        Assertions.assertEquals(List.of(EventCommandMessages.NOT_FOUND.asComponent()), messages);
    }

    @Test
    void testCommonFieldsPlayerNameAndCommunicationTextAreRendered() {
        var event = new EventDetail(
            EVENT_ID,
            CHAT,
            new PayloadGeneration(2),
            OCCURRED_AT,
            Optional.of(Key.key("example", "server")),
            Optional.of(Key.key("minecraft", "overworld")),
            OptionalInt.of(1),
            OptionalInt.of(-2),
            OptionalInt.of(3),
            Optional.of(SearchQuery.ActorKind.PLAYER),
            Optional.of(PLAYER_ID),
            Optional.of("Alice"),
            Optional.empty(),
            Optional.of(Key.key("minecraft", "stone")),
            EXPIRES_AT,
            Optional.of("hello\nworld")
        );

        var lines = EventCommandSupport.formatEvent(event);

        Assertions.assertEquals(11, lines.size());
        Assertions.assertTrue(lines.contains(
            EventCommandMessages.EVENT_ID.asComponent()
                .append(Component.text(": "))
                .append(Component.text(EVENT_ID.toString()))
        ));
        Assertions.assertTrue(lines.contains(
            EventCommandMessages.EVENT_TYPE.asComponent()
                .append(Component.text(": "))
                .append(Component.text("paper_chat").hoverEvent(
                    HoverEvent.showText(Component.text("kansokusha:paper_chat"))
                ))
        ));
        Assertions.assertTrue(lines.contains(
            EventCommandMessages.ACTOR.asComponent()
                .append(Component.text(": "))
                .append(
                    EventCommandMessages.ACTOR_PLAYER.asComponent()
                        .append(Component.space())
                        .append(Component.text("Alice"))
                        .hoverEvent(HoverEvent.showText(Component.text(PLAYER_ID.toString())))
                )
        ));
        Assertions.assertTrue(lines.contains(
            EventCommandMessages.COMMUNICATION_TEXT.asComponent()
                .append(Component.text(": "))
                .append(Component.text("hello world"))
        ));
    }

    @Test
    void testOptionalFieldsAreOmitted() {
        var lines = EventCommandSupport.formatEvent(minimalEvent(BREAK));

        Assertions.assertEquals(5, lines.size());
        Assertions.assertFalse(lines.stream().anyMatch(line ->
            line.equals(EventCommandMessages.SERVER.asComponent())
                || line.equals(EventCommandMessages.WORLD.asComponent())
                || line.equals(EventCommandMessages.POSITION.asComponent())
                || line.equals(EventCommandMessages.ACTOR.asComponent())
                || line.equals(EventCommandMessages.TARGET_TYPE.asComponent())
        ));
    }

    @Test
    void testEntityAndBlockActorsAreReadable() {
        var entityId = UUID.fromString("123e4567-e89b-12d3-a456-426614174502");
        var entity = detailWithActor(
            SearchQuery.ActorKind.ENTITY,
            Optional.of(entityId),
            Optional.of(Key.key("minecraft", "creeper"))
        );
        var block = detailWithActor(
            SearchQuery.ActorKind.BLOCK,
            Optional.empty(),
            Optional.of(Key.key("minecraft", "piston"))
        );

        Assertions.assertTrue(EventCommandSupport.formatEvent(entity).contains(
            EventCommandMessages.ACTOR.asComponent()
                .append(Component.text(": "))
                .append(
                    EventCommandMessages.ACTOR_ENTITY.asComponent()
                        .append(Component.space())
                        .append(Component.text("minecraft:creeper"))
                        .hoverEvent(HoverEvent.showText(Component.text(entityId.toString())))
                )
        ));
        Assertions.assertTrue(EventCommandSupport.formatEvent(block).contains(
            EventCommandMessages.ACTOR.asComponent()
                .append(Component.text(": "))
                .append(
                    EventCommandMessages.ACTOR_BLOCK.asComponent()
                        .append(Component.space())
                        .append(Component.text("minecraft:piston"))
                )
        ));
    }

    @Test
    void testNonCommunicationEventDoesNotRenderSearchText() {
        var event = new EventDetail(
            EVENT_ID,
            BREAK,
            PayloadGeneration.FIRST,
            OCCURRED_AT,
            Optional.empty(),
            Optional.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            EXPIRES_AT,
            Optional.of("derived text must stay hidden")
        );

        var textLabel = EventCommandMessages.COMMUNICATION_TEXT.asComponent();
        Assertions.assertFalse(EventCommandSupport.formatEvent(event).stream().anyMatch(line ->
            line.children().contains(textLabel)
        ));
        Assertions.assertEquals(5, EventCommandSupport.formatEvent(event).size());
    }

    @Test
    void testJapaneseBundleContainsEveryEventMessageKey() throws Exception {
        var properties = new Properties();
        try (
            var input = EventCommandSupportTest.class.getClassLoader()
                .getResourceAsStream("languages/ja.properties")
        ) {
            Assertions.assertNotNull(input);
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }

        Assertions.assertTrue(
            properties.stringPropertyNames().containsAll(
                EventCommandMessages.DEFINER.getCollectedMessages().keySet()
            )
        );
    }

    private static EventDetail minimalEvent(Key eventType) {
        return new EventDetail(
            EVENT_ID,
            eventType,
            PayloadGeneration.FIRST,
            OCCURRED_AT,
            Optional.empty(),
            Optional.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            EXPIRES_AT,
            Optional.empty()
        );
    }

    private static EventDetail detailWithActor(
        SearchQuery.ActorKind kind,
        Optional<UUID> uuid,
        Optional<Key> type
    ) {
        return new EventDetail(
            EVENT_ID,
            BREAK,
            PayloadGeneration.FIRST,
            OCCURRED_AT,
            Optional.empty(),
            Optional.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            Optional.of(kind),
            uuid,
            Optional.empty(),
            type,
            Optional.empty(),
            EXPIRES_AT,
            Optional.empty()
        );
    }

    private static final class TestApi implements KansokushaApi, EventSearchBackend {

        private UUID lookupId;
        private CompletableFuture<Optional<EventDetail>> lookup =
            CompletableFuture.completedFuture(Optional.empty());

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
        public boolean submitSearchable(EventSubmission submission, String searchText) {
            return true;
        }

        @Override
        public CompletableFuture<SearchPage> search(SearchRequest request) {
            return CompletableFuture.completedFuture(
                new SearchPage(List.of(), Optional.empty(), Optional.empty())
            );
        }

        @Override
        public CompletableFuture<Optional<EventDetail>> findEvent(UUID eventId) {
            this.lookupId = eventId;
            return this.lookup;
        }

        @Override
        public CompletableFuture<List<UUID>> findEventIdsContaining(String literal) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
