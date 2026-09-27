package net.okocraft.kansokusha.common.storage.duckdb;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.actor.BlockActor;
import net.okocraft.kansokusha.api.actor.EntityActor;
import net.okocraft.kansokusha.api.actor.EventActor;
import net.okocraft.kansokusha.api.actor.PlayerActor;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQueryParser;
import net.okocraft.kansokusha.common.storage.PlayerNameObservation;
import net.okocraft.kansokusha.common.storage.QueuedEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class DuckDbEventSearchTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC);
    private static final Key AUDIT = Key.key("example", "audit");
    private static final Key OTHER = Key.key("example", "other");
    private static final Key LOGIN = Key.key("example", "login");
    private static final Key SERVER = Key.key("example", "server");
    private static final Key OVERWORLD = Key.key("minecraft", "overworld");
    private static final Key NETHER = Key.key("minecraft", "the_nether");
    private static final Key STONE = Key.key("minecraft", "stone");
    private static final Key DIRT = Key.key("minecraft", "dirt");
    private static final UUID PLAYER_ONE =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174201");
    private static final UUID PLAYER_TWO =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174202");
    private static final UUID ENTITY =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174203");

    @Test
    void testSearchRequestEnforcesBackendPageSizeLimit() {
        var atMaximum = request(
            "limit " + SearchRequest.MAX_LIMIT,
            Set.of(AUDIT),
            Optional.empty(),
            Optional.empty(),
            20
        );
        Assertions.assertEquals(SearchRequest.MAX_LIMIT, atMaximum.limit());

        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> request(
                "limit " + (SearchRequest.MAX_LIMIT + 1),
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            )
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> request(
                "limit 2147483647",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            )
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> request(
                "",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                SearchRequest.MAX_LIMIT + 1
            )
        );
    }

    @Test
    void testHistoricalSearchMetadataUsesPersistedDistinctValues(@TempDir Path dir) throws Exception {
        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(
                queued(event(
                    AUDIT,
                    NOW,
                    OVERWORLD,
                    new BlockPosition(10, 64, 20),
                    new PlayerActor(PLAYER_ONE),
                    STONE
                ), null),
                queued(event(
                    OTHER,
                    NOW.plusSeconds(1),
                    NETHER,
                    new BlockPosition(1, 70, 2),
                    new EntityActor(ENTITY, Key.key("minecraft", "creeper")),
                    DIRT
                ), null)
            ));

            var metadata = storage.searchMetadata();
            Assertions.assertEquals(Set.of(AUDIT, OTHER), metadata.eventTypes());
            Assertions.assertEquals(Set.of(OVERWORLD, NETHER), metadata.worlds());
            Assertions.assertEquals(
                Set.of(Key.key("minecraft", "creeper")),
                metadata.actorTypes()
            );
            Assertions.assertEquals(Set.of(STONE, DIRT), metadata.targetTypes());
        }
    }

    @Test
    void testTypedConditionsProjectionAndPermissionScope(@TempDir Path dir) throws Exception {
        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(
                queued(
                    event(
                        AUDIT,
                        NOW,
                        OVERWORLD,
                        new BlockPosition(10, 64, 20),
                        new PlayerActor(PLAYER_ONE),
                        STONE
                    ),
                    "Hello BAN%_* world"
                ),
                queued(
                    event(
                        OTHER,
                        NOW.plusSeconds(1),
                        OVERWORLD,
                        new BlockPosition(12, 200, 18),
                        new EntityActor(ENTITY, Key.key("minecraft", "creeper")),
                        DIRT
                    ),
                    "other text"
                ),
                queued(
                    event(
                        AUDIT,
                        NOW.plusSeconds(2),
                        NETHER,
                        new BlockPosition(10, 80, 20),
                        new BlockActor(Key.key("minecraft", "piston")),
                        STONE
                    ),
                    null
                )
            ));

            var page = storage.search(request(
                "action example:audit target minecraft:stone filter \"ban%_*\"",
                Set.of(AUDIT, OTHER),
                Optional.empty(),
                Optional.empty(),
                20
            ));

            Assertions.assertEquals(1, page.events().size());
            var result = page.events().getFirst();
            Assertions.assertEquals(AUDIT, result.eventType());
            Assertions.assertEquals(NOW, result.occurredAt());
            Assertions.assertEquals(Optional.of(SERVER), result.server());
            Assertions.assertEquals(Optional.of(OVERWORLD), result.world());
            Assertions.assertEquals(10, result.x().orElseThrow());
            Assertions.assertEquals(64, result.y().orElseThrow());
            Assertions.assertEquals(20, result.z().orElseThrow());
            Assertions.assertEquals(Optional.of(PLAYER_ONE), result.actorUuid());
            Assertions.assertEquals(Optional.of(STONE), result.targetType());
            Assertions.assertEquals(Optional.of("Hello BAN%_* world"), result.searchText());

            Assertions.assertTrue(storage.search(request(
                "action example:audit",
                Set.of(OTHER),
                Optional.empty(),
                Optional.empty(),
                20
            )).events().isEmpty());
        }
    }

    @Test
    void testUserUsesNewestHistoricalUuidWhileActorUuidIsDirect(@TempDir Path dir) throws Exception {
        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(queuedLogin(PLAYER_ONE, "SharedName", NOW)));
            storage.append(List.of(queued(
                event(AUDIT, NOW.plusSeconds(1), null, null, new PlayerActor(PLAYER_ONE), null),
                null
            )));
            storage.append(List.of(queuedLogin(PLAYER_TWO, "SharedName", NOW.plusSeconds(2))));
            storage.append(List.of(queued(
                event(AUDIT, NOW.plusSeconds(3), null, null, new PlayerActor(PLAYER_TWO), null),
                null
            )));

            var shared = storage.search(request(
                "user sharedname action example:audit",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            Assertions.assertEquals(1, shared.events().size());
            Assertions.assertEquals(PLAYER_TWO, shared.events().getFirst().actorUuid().orElseThrow());
            Assertions.assertEquals(Optional.of("SharedName"), shared.events().getFirst().actorName());

            storage.append(List.of(queuedLogin(
                PLAYER_ONE,
                "SharedName",
                NOW.plusSeconds(4)
            )));
            storage.append(List.of(queued(
                event(AUDIT, NOW.plusSeconds(5), null, null, new PlayerActor(PLAYER_ONE), null),
                null
            )));

            var reassigned = storage.search(request(
                "user SHAREDNAME action example:audit",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            Assertions.assertEquals(2, reassigned.events().size());
            Assertions.assertTrue(reassigned.events().stream().allMatch(
                result -> result.actorUuid().orElseThrow().equals(PLAYER_ONE)
            ));

            var direct = storage.search(request(
                "actor-uuid " + PLAYER_TWO + " action example:audit",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            Assertions.assertEquals(1, direct.events().size());
            Assertions.assertEquals(PLAYER_TWO, direct.events().getFirst().actorUuid().orElseThrow());
        }
    }

    @Test
    void testRadiusAndAroundUseInclusiveXZSquareWithoutYBound(@TempDir Path dir) throws Exception {
        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(
                queued(event(
                    AUDIT,
                    NOW,
                    OVERWORLD,
                    new BlockPosition(12, 300, 18),
                    new PlayerActor(PLAYER_ONE),
                    null
                ), null),
                queued(event(
                    AUDIT,
                    NOW.plusSeconds(1),
                    OVERWORLD,
                    new BlockPosition(13, -100, 18),
                    new PlayerActor(PLAYER_ONE),
                    null
                ), null),
                queued(event(
                    AUDIT,
                    NOW.plusSeconds(2),
                    NETHER,
                    new BlockPosition(10, 64, 20),
                    new PlayerActor(PLAYER_ONE),
                    null
                ), null)
            ));

            var center = new SearchRequest.RadiusCenter(OVERWORLD, 10, 20);
            var radius = storage.search(request(
                "radius 2",
                Set.of(AUDIT),
                Optional.of(center),
                Optional.empty(),
                20
            ));
            Assertions.assertEquals(1, radius.events().size());
            Assertions.assertEquals(300, radius.events().getFirst().y().orElseThrow());

            var around = storage.search(request(
                "around minecraft:overworld 10 20 2",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            Assertions.assertEquals(
                radius.events().stream().map(SearchPage.Event::eventId).toList(),
                around.events().stream().map(SearchPage.Event::eventId).toList()
            );

            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> request(
                    "radius 2",
                    Set.of(AUDIT),
                    Optional.empty(),
                    Optional.empty(),
                    20
                )
            );
        }
    }

    @Test
    void testExclusionsUseGroupedPredicate(@TempDir Path dir) throws Exception {
        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(List.of(
                queued(event(
                    AUDIT,
                    NOW,
                    null,
                    null,
                    new EntityActor(ENTITY, Key.key("minecraft", "creeper")),
                    DIRT
                ), null),
                queued(event(
                    AUDIT,
                    NOW.plusSeconds(1),
                    null,
                    null,
                    new EntityActor(UUID.randomUUID(), Key.key("minecraft", "zombie")),
                    STONE
                ), null),
                queued(event(
                    AUDIT,
                    NOW.plusSeconds(2),
                    null,
                    null,
                    new PlayerActor(PLAYER_ONE),
                    DIRT
                ), null)
            ));

            var page = storage.search(request(
                "exclude actor-kind entity exclude target minecraft:dirt",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));

            Assertions.assertEquals(2, page.events().size());
            Assertions.assertTrue(page.events().stream().noneMatch(
                result -> result.actorUuid().orElseThrow().equals(ENTITY)
            ));
        }
    }

    @Test
    void testExclusionsKeepRowsWhoseNullableFieldsDoNotMatch(@TempDir Path dir) throws Exception {
        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            var noTargetAt = NOW;
            var dirtAt = NOW.plusSeconds(1);
            var playerAt = NOW.plusSeconds(2);
            var creeperAt = NOW.plusSeconds(3);
            var noWorldAt = NOW.plusSeconds(4);
            var overworldAt = NOW.plusSeconds(5);
            var worldWithoutPositionAt = NOW.plusSeconds(6);
            var exactPositionAt = NOW.plusSeconds(7);

            storage.append(List.of(
                queued(event(
                    AUDIT,
                    noTargetAt,
                    null,
                    null,
                    new PlayerActor(PLAYER_ONE),
                    null
                ), null),
                queued(event(
                    AUDIT,
                    dirtAt,
                    null,
                    null,
                    new PlayerActor(PLAYER_ONE),
                    DIRT
                ), null),
                queued(event(
                    AUDIT,
                    playerAt,
                    null,
                    null,
                    new PlayerActor(PLAYER_TWO),
                    STONE
                ), null),
                queued(event(
                    AUDIT,
                    creeperAt,
                    null,
                    null,
                    new EntityActor(ENTITY, Key.key("minecraft", "creeper")),
                    STONE
                ), null),
                queued(event(
                    AUDIT,
                    noWorldAt,
                    null,
                    null,
                    new PlayerActor(PLAYER_ONE),
                    STONE
                ), null),
                queued(event(
                    AUDIT,
                    overworldAt,
                    OVERWORLD,
                    new BlockPosition(5, 64, 5),
                    new PlayerActor(PLAYER_ONE),
                    STONE
                ), null),
                queued(event(
                    AUDIT,
                    worldWithoutPositionAt,
                    OVERWORLD,
                    null,
                    new PlayerActor(PLAYER_ONE),
                    STONE
                ), null),
                queued(event(
                    AUDIT,
                    exactPositionAt,
                    OVERWORLD,
                    new BlockPosition(1, 2, 3),
                    new PlayerActor(PLAYER_ONE),
                    STONE
                ), null)
            ));

            var excludeTarget = storage.search(request(
                "exclude target minecraft:dirt",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            Assertions.assertTrue(excludeTarget.events().stream().anyMatch(
                result -> result.occurredAt().equals(noTargetAt)
            ));
            Assertions.assertTrue(excludeTarget.events().stream().noneMatch(
                result -> result.occurredAt().equals(dirtAt)
            ));

            var excludeActorType = storage.search(request(
                "exclude actor-type minecraft:creeper",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            Assertions.assertTrue(excludeActorType.events().stream().anyMatch(
                result -> result.occurredAt().equals(playerAt)
            ));
            Assertions.assertTrue(excludeActorType.events().stream().noneMatch(
                result -> result.occurredAt().equals(creeperAt)
            ));

            var excludeWorld = storage.search(request(
                "exclude world minecraft:overworld",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            Assertions.assertTrue(excludeWorld.events().stream().anyMatch(
                result -> result.occurredAt().equals(noWorldAt)
            ));
            Assertions.assertTrue(excludeWorld.events().stream().noneMatch(
                result -> result.occurredAt().equals(overworldAt)
            ));

            var excludePosition = storage.search(request(
                "exclude position minecraft:overworld 1 2 3",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            Assertions.assertTrue(excludePosition.events().stream().anyMatch(
                result -> result.occurredAt().equals(worldWithoutPositionAt)
            ));
            Assertions.assertTrue(excludePosition.events().stream().noneMatch(
                result -> result.occurredAt().equals(exactPositionAt)
            ));
        }
    }

    @Test
    void testKeysetPaginationRemainsStableAcrossNewInsertAndSupportsBothOrders(
        @TempDir Path dir
    ) throws Exception {
        try (var storage = DuckDbStorageImpl.open(dir.resolve("kansokusha.duckdb"))) {
            storage.append(java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> queued(event(
                    AUDIT,
                    NOW,
                    null,
                    null,
                    new PlayerActor(PLAYER_ONE),
                    null
                ), null))
                .toList());

            var first = storage.search(request(
                "limit 2",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            Assertions.assertEquals(2, first.events().size());
            Assertions.assertTrue(first.previousCursor().isEmpty());
            Assertions.assertTrue(first.nextCursor().isPresent());

            storage.append(List.of(queued(event(
                AUDIT,
                NOW,
                null,
                null,
                new PlayerActor(PLAYER_TWO),
                null
            ), null)));

            var second = storage.search(request(
                "limit 2",
                Set.of(AUDIT),
                Optional.empty(),
                first.nextCursor(),
                20
            ));
            var third = storage.search(request(
                "limit 2",
                Set.of(AUDIT),
                Optional.empty(),
                second.nextCursor(),
                20
            ));

            var originalIds = new ArrayList<UUID>();
            first.events().forEach(result -> originalIds.add(result.eventId()));
            second.events().forEach(result -> originalIds.add(result.eventId()));
            third.events().forEach(result -> originalIds.add(result.eventId()));

            Assertions.assertEquals(5, originalIds.size());
            Assertions.assertEquals(5, Set.copyOf(originalIds).size());
            Assertions.assertTrue(third.nextCursor().isEmpty());

            var allNewest = storage.search(request(
                "limit 10",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            Assertions.assertEquals(6, allNewest.events().size());
            var insertedId = allNewest.events().getFirst().eventId();
            Assertions.assertFalse(originalIds.contains(insertedId));

            var previous = storage.search(request(
                "limit 2",
                Set.of(AUDIT),
                Optional.empty(),
                second.previousCursor(),
                20
            ));
            Assertions.assertEquals(
                first.events().stream().map(SearchPage.Event::eventId).toList(),
                previous.events().stream().map(SearchPage.Event::eventId).toList()
            );

            var oldest = storage.search(request(
                "order oldest limit 10",
                Set.of(AUDIT),
                Optional.empty(),
                Optional.empty(),
                20
            ));
            var expectedOldest = new ArrayList<>(
                allNewest.events().stream().map(SearchPage.Event::eventId).toList()
            );
            Collections.reverse(expectedOldest);
            Assertions.assertEquals(
                expectedOldest,
                oldest.events().stream().map(SearchPage.Event::eventId).toList()
            );
        }
    }

    private static SearchRequest request(
        String input,
        Set<Key> allowedEventTypes,
        Optional<SearchRequest.RadiusCenter> center,
        Optional<SearchRequest.Cursor> cursor,
        int defaultLimit
    ) {
        return new SearchRequest(
            SearchQueryParser.parse(input, CLOCK, ZoneOffset.UTC),
            new SearchRequest.Constraints(allowedEventTypes),
            center,
            cursor,
            defaultLimit
        );
    }

    private static QueuedEvent queued(EventSubmission event, String searchText) {
        return new QueuedEvent(
            event,
            event.occurredAt().toEpochMilli(),
            event.occurredAt().plus(Duration.ofDays(1)).toEpochMilli(),
            null,
            searchText
        );
    }

    private static QueuedEvent queuedLogin(UUID playerId, String username, Instant occurredAt) {
        var event = event(LOGIN, occurredAt, null, null, new PlayerActor(playerId), null);
        return new QueuedEvent(
            event,
            occurredAt.toEpochMilli(),
            occurredAt.plus(Duration.ofDays(30)).toEpochMilli(),
            new PlayerNameObservation(
                username,
                occurredAt.plus(Duration.ofDays(30)).toEpochMilli()
            ),
            null
        );
    }

    private static EventSubmission event(
        Key type,
        Instant occurredAt,
        Key world,
        BlockPosition position,
        EventActor actor,
        Key target
    ) {
        return new EventSubmission(
            type,
            PayloadGeneration.FIRST,
            occurredAt,
            world == null ? null : SERVER,
            world,
            position,
            actor,
            target,
            EventPayload.copyOf(new byte[]{1})
        );
    }
}
