package net.okocraft.kansokusha.common.runtime;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.RegistrationOutcome;
import net.okocraft.kansokusha.api.SubmissionOutcome;
import net.okocraft.kansokusha.api.event.EventPayload;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import net.okocraft.kansokusha.api.position.BlockPosition;
import net.okocraft.kansokusha.api.subject.PlayerSubject;
import net.okocraft.kansokusha.common.storage.DuckDbDatabase;
import net.okocraft.kansokusha.common.storage.DuckDbEventTypeRegistry;
import net.okocraft.kansokusha.common.storage.DuckDbMigrations;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;

class KansokushaRuntimePersistenceIntegrationTest {

    private static final Key EVENT_TYPE = Key.key("example", "persistence");
    private static final Key SERVER = Key.key("example", "paper");
    private static final Key WORLD = Key.key("minecraft", "overworld");
    private static final Key RETENTION = Key.key("example", "audit");
    private static final UUID PLAYER =
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void testPublicApiPersistsAcrossRestartAndKeepsStableRegistryIdentity(
        @TempDir Path dir
    ) throws Exception {
        writeConfig(dir);
        var failures = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();

        var generationOne = new EventTypeDefinition(
            EVENT_TYPE,
            PayloadGeneration.FIRST
        );
        try (
            var runtime = KansokushaRuntime.start(
                dir,
                SERVER,
                (message, failure) -> failures.add(failure)
            )
        ) {
            Assertions.assertEquals(
                RegistrationOutcome.REGISTERED,
                runtime.api().registerEventType(generationOne)
            );
            Assertions.assertEquals(
                SubmissionOutcome.ACCEPTED,
                runtime.api().submit(
                    submission(
                        PayloadGeneration.FIRST,
                        Instant.parse("2026-09-21T00:00:00.123456Z"),
                        new byte[]{1, 2, 3, 4}
                    )
                )
            );
        }

        Assertions.assertTrue(failures.isEmpty(), failures::toString);
        var databasePath = dir.resolve(KansokushaRuntime.DATABASE_FILENAME);
        var firstIdentity = inspectFirstRestart(databasePath);

        var generationTwo = new EventTypeDefinition(
            EVENT_TYPE,
            new PayloadGeneration(2)
        );
        try (
            var runtime = KansokushaRuntime.start(
                dir,
                SERVER,
                (message, failure) -> failures.add(failure)
            )
        ) {
            Assertions.assertEquals(
                RegistrationOutcome.REGISTERED,
                runtime.api().registerEventType(generationTwo)
            );
            Assertions.assertEquals(
                SubmissionOutcome.ACCEPTED,
                runtime.api().submit(
                    submission(
                        new PayloadGeneration(2),
                        Instant.parse("2026-09-22T01:02:03.456789Z"),
                        new byte[]{9, 8, 7}
                    )
                )
            );
        }

        Assertions.assertTrue(failures.isEmpty(), failures::toString);
        inspectSecondRestart(databasePath, firstIdentity);
    }

    private static PersistentIdentity inspectFirstRestart(Path databasePath)
        throws Exception {
        try (var database = DuckDbDatabase.open(databasePath)) {
            DuckDbMigrations.migrate(database);
            var registry = new DuckDbEventTypeRegistry(database);
            var eventType = registry.findEventType(EVENT_TYPE).orElseThrow();
            var generationId = generationId(
                database.connection(),
                eventType.id(),
                PayloadGeneration.FIRST.value()
            );

            var recoveredGeneration = registry.findPayloadGeneration(generationId)
                .orElseThrow();
            Assertions.assertEquals(eventType, recoveredGeneration.eventType());
            Assertions.assertEquals(
                PayloadGeneration.FIRST,
                recoveredGeneration.generation()
            );

            try (
                var statement = database.connection().createStatement();
                var rows = statement.executeQuery(
                    """
                        SELECT
                            et.id AS event_type_id,
                            pg.id AS payload_generation_id,
                            et.event_type_key,
                            pg.generation,
                            epoch_ms(e.occurred_at) AS occurred_ms,
                            s.server_key,
                            w.world_key,
                            e.block_x,
                            e.block_y,
                            e.block_z,
                            CAST(e.subject_player_uuid AS VARCHAR) AS player_uuid,
                            rp.retention_policy_key,
                            epoch_ms(e.expires_at) AS expires_ms,
                            hex(e.payload) AS payload_hex
                        FROM events e
                        JOIN payload_generations pg ON pg.id = e.payload_generation_id
                        JOIN event_types et ON et.id = pg.event_type_id
                        JOIN servers s ON s.id = e.server_id
                        LEFT JOIN worlds w ON w.id = e.world_id
                        JOIN retention_policies rp ON rp.id = e.retention_policy_id
                        """
                )
            ) {
                Assertions.assertTrue(rows.next());
                Assertions.assertEquals(eventType.id(), rows.getInt("event_type_id"));
                Assertions.assertEquals(generationId, rows.getInt("payload_generation_id"));
                Assertions.assertEquals(EVENT_TYPE.asString(), rows.getString("event_type_key"));
                Assertions.assertEquals(1, rows.getInt("generation"));
                Assertions.assertEquals(
                    Instant.parse("2026-09-21T00:00:00.123Z").toEpochMilli(),
                    rows.getLong("occurred_ms")
                );
                Assertions.assertEquals(SERVER.asString(), rows.getString("server_key"));
                Assertions.assertEquals(WORLD.asString(), rows.getString("world_key"));
                Assertions.assertEquals(12, rows.getInt("block_x"));
                Assertions.assertEquals(64, rows.getInt("block_y"));
                Assertions.assertEquals(-7, rows.getInt("block_z"));
                Assertions.assertEquals(PLAYER.toString(), rows.getString("player_uuid"));
                Assertions.assertEquals(RETENTION.asString(), rows.getString("retention_policy_key"));
                Assertions.assertEquals(
                    Instant.parse("2026-09-23T00:00:00.123456Z").toEpochMilli(),
                    rows.getLong("expires_ms")
                );
                Assertions.assertEquals("01020304", rows.getString("payload_hex"));
                Assertions.assertFalse(rows.next());
            }

            return new PersistentIdentity(eventType.id(), generationId);
        }
    }

    private static void inspectSecondRestart(
        Path databasePath,
        PersistentIdentity firstIdentity
    ) throws Exception {
        try (var database = DuckDbDatabase.open(databasePath)) {
            DuckDbMigrations.migrate(database);
            var registry = new DuckDbEventTypeRegistry(database);
            var eventType = registry.findEventType(EVENT_TYPE).orElseThrow();

            Assertions.assertEquals(firstIdentity.eventTypeId(), eventType.id());
            Assertions.assertEquals(
                firstIdentity.generationOneId(),
                generationId(database.connection(), eventType.id(), 1)
            );
            var generationTwoId = generationId(
                database.connection(),
                eventType.id(),
                2
            );
            Assertions.assertNotEquals(
                firstIdentity.generationOneId(),
                generationTwoId
            );

            Assertions.assertEquals(
                PayloadGeneration.FIRST,
                registry.findPayloadGeneration(firstIdentity.generationOneId())
                    .orElseThrow()
                    .generation()
            );
            Assertions.assertEquals(
                new PayloadGeneration(2),
                registry.findPayloadGeneration(generationTwoId)
                    .orElseThrow()
                    .generation()
            );

            try (
                var statement = database.connection().prepareStatement(
                    """
                        SELECT count(*) AS event_count,
                               count(DISTINCT pg.event_type_id) AS event_type_count,
                               count(DISTINCT pg.id) AS generation_count
                        FROM events e
                        JOIN payload_generations pg ON pg.id = e.payload_generation_id
                        JOIN event_types et ON et.id = pg.event_type_id
                        WHERE et.event_type_key = ?
                        """
                )
            ) {
                statement.setString(1, EVENT_TYPE.asString());
                try (var rows = statement.executeQuery()) {
                    Assertions.assertTrue(rows.next());
                    Assertions.assertEquals(2, rows.getInt("event_count"));
                    Assertions.assertEquals(1, rows.getInt("event_type_count"));
                    Assertions.assertEquals(2, rows.getInt("generation_count"));
                }
            }
        }
    }

    private static EventSubmission submission(
        PayloadGeneration generation,
        Instant occurredAt,
        byte[] payload
    ) {
        return new EventSubmission(
            EVENT_TYPE,
            generation,
            occurredAt,
            SERVER,
            WORLD,
            new BlockPosition(12, 64, -7),
            new PlayerSubject(PLAYER),
            EventPayload.copyOf(payload)
        );
    }

    private static int generationId(
        Connection connection,
        int eventTypeId,
        int generation
    ) throws Exception {
        try (
            var statement = connection.prepareStatement(
                """
                    SELECT id
                    FROM payload_generations
                    WHERE event_type_id = ? AND generation = ?
                    """
            )
        ) {
            statement.setInt(1, eventTypeId);
            statement.setInt(2, generation);
            try (var rows = statement.executeQuery()) {
                Assertions.assertTrue(rows.next());
                return rows.getInt("id");
            }
        }
    }

    private static void writeConfig(Path dir) throws Exception {
        Files.writeString(
            dir.resolve("config.yml"),
            """
                ingestion:
                  queue-capacity: 8
                  max-batch-size: 8
                  max-batch-delay: PT1H
                retention:
                  policies:
                    - key: example:audit
                      duration: P2D
                  event-type-mappings:
                    - event-type: example:persistence
                      policy: example:audit
                  fallback-policy: example:audit
                  cleanup-interval: PT1H
                  max-rows-per-pass: 100
                """
        );
    }

    private record PersistentIdentity(
        int eventTypeId,
        int generationOneId
    ) {
    }
}
