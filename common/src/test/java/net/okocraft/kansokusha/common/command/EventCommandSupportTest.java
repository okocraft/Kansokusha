package net.okocraft.kansokusha.common.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventSearchBackend;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
            new SearchPage.Event(
                EVENT_ID,
                CHAT,
                OCCURRED_AT,
                Optional.of(Key.key("example", "server")),
                Optional.of(Key.key("minecraft", "overworld")),
                Optional.of(new BlockPosition(1, -2, 3)),
                Optional.of(new PlayerActor(PLAYER_ID)),
                Optional.of("Alice"),
                Optional.of(Key.key("minecraft", "stone")),
                Optional.of("hello\nworld")
            ),
            new PayloadGeneration(2),
            EXPIRES_AT
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
        var entity = detailWithActor(new EntityActor(entityId, Key.key("minecraft", "creeper")));
        var block = detailWithActor(new BlockActor(Key.key("minecraft", "piston")));

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

    private static EventDetail minimalEvent(Key eventType) {
        return event(eventType, Optional.empty());
    }

    private static EventDetail detailWithActor(EventActor actor) {
        return event(BREAK, Optional.of(actor));
    }

    private static EventDetail event(Key eventType, Optional<EventActor> actor) {
        return new EventDetail(
            new SearchPage.Event(
                EVENT_ID,
                eventType,
                OCCURRED_AT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                actor,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            ),
            PayloadGeneration.FIRST,
            EXPIRES_AT
        );
    }

    private static final class TestApi implements EventSearchBackend {

        private UUID lookupId;
        private CompletableFuture<Optional<EventDetail>> lookup =
            CompletableFuture.completedFuture(Optional.empty());

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
        public CompletableFuture<SearchMetadata> searchMetadata() {
            return CompletableFuture.completedFuture(SearchMetadata.empty());
        }

        @Override
        public CompletableFuture<List<String>> offlinePlayerNames() {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
