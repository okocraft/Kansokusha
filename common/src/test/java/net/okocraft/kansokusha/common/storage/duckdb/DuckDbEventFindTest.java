package net.okocraft.kansokusha.common.storage.duckdb;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.common.storage.QueuedEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

class DuckDbEventFindTest {

    private static final Instant NOW = Instant.parse("2026-09-27T09:30:00.123Z");
    private static final Key CHAT = Key.key("kansokusha", "paper_chat");

    @Test
    void testFindPersistedUuidV7EventWithDerivedDataAndUnknownId(@TempDir Path dir)
        throws Exception {
        var playerId = UUID.fromString("123e4567-e89b-12d3-a456-426614174510");
        var expiresAt = NOW.plus(Duration.ofDays(10));
        var submission = new EventSubmission(
            CHAT,
            new PayloadGeneration(2),
            NOW,
            Key.key("example", "server"),
            Key.key("minecraft", "overworld"),
            new BlockPosition(4, 5, 6),
            new PlayerActor(playerId),
            Key.key("minecraft", "message"),
            EventPayload.copyOf(new byte[]{1, 2, 3})
        );

        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(new QueuedEvent(
                submission,
                NOW.toEpochMilli(),
                expiresAt.toEpochMilli(),
                "Alice",
                "hello from projection"
            )));

            var page = storage.search(new SearchRequest(
                new SearchQuery(
                    SearchQuery.Conditions.empty(),
                    SearchQuery.Conditions.empty(),
                    SearchQuery.Order.NEWEST,
                    OptionalInt.empty()
                ),
                new SearchRequest.Constraints(Set.of(CHAT)),
                Optional.empty(),
                10
            ));
            Assertions.assertEquals(1, page.events().size());
            var eventId = page.events().getFirst().eventId();
            Assertions.assertEquals(7, eventId.version());

            var detail = storage.findEvent(eventId).orElseThrow();
            var event = detail.event();
            Assertions.assertEquals(page.events().getFirst(), event);
            Assertions.assertEquals(eventId, event.eventId());
            Assertions.assertEquals(CHAT, event.eventType());
            Assertions.assertEquals(new PayloadGeneration(2), detail.payloadGeneration());
            Assertions.assertEquals(NOW, event.occurredAt());
            Assertions.assertEquals(Optional.of(Key.key("example", "server")), event.server());
            Assertions.assertEquals(Optional.of(Key.key("minecraft", "overworld")), event.world());
            Assertions.assertEquals(Optional.of(new BlockPosition(4, 5, 6)), event.position());
            Assertions.assertEquals(Optional.of(new PlayerActor(playerId)), event.actor());
            Assertions.assertEquals(Optional.of("Alice"), event.actorName());
            Assertions.assertEquals(Optional.of(Key.key("minecraft", "message")), event.targetType());
            Assertions.assertEquals(expiresAt, detail.expiresAt());
            Assertions.assertEquals(Optional.of("hello from projection"), event.searchText());

            Assertions.assertTrue(storage.findEvent(
                UUID.fromString("0199a123-4567-789a-8bcd-ef0123456788")
            ).isEmpty());
        }
    }
}
