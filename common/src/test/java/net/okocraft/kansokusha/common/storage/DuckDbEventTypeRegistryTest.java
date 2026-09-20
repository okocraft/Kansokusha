package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.Executors;

class DuckDbEventTypeRegistryTest {

    private static final Key EVENT_KEY = Key.key("example", "block_break");

    @Test
    void testIdentitySurvivesReopenAndReregistration(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("registry.duckdb");

        DuckDbEventTypeRegistry.PersistentPayloadGeneration first;
        try (var database = DuckDbDatabase.open(filepath)) {
            DuckDbMigrations.migrate(database.connection());
            var registry = new DuckDbEventTypeRegistry(database.connection());

            first = registry.resolve(definition(1));
            Assertions.assertEquals(EVENT_KEY, first.eventType().key());
            Assertions.assertEquals(new PayloadGeneration(1), first.generation());
        }

        try (var database = DuckDbDatabase.open(filepath)) {
            DuckDbMigrations.migrate(database.connection());
            var registry = new DuckDbEventTypeRegistry(database.connection());

            var reregistered = registry.resolve(definition(1));
            Assertions.assertEquals(first, reregistered);
            Assertions.assertEquals(
                first.eventType(),
                registry.findEventType(first.eventType().id()).orElseThrow()
            );
            Assertions.assertEquals(
                first,
                registry.findPayloadGeneration(first.id()).orElseThrow()
            );
            Assertions.assertEquals(1, count(database.connection(), "event_types"));
            Assertions.assertEquals(1, count(database.connection(), "payload_generations"));
        }
    }

    @Test
    void testMultiplePayloadGenerationsRemainDistinct(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("generations.duckdb"))) {
            DuckDbMigrations.migrate(database.connection());
            var registry = new DuckDbEventTypeRegistry(database.connection());

            var first = registry.resolve(definition(1));
            var second = registry.resolve(definition(2));

            Assertions.assertEquals(first.eventType(), second.eventType());
            Assertions.assertNotEquals(first.id(), second.id());
            Assertions.assertEquals(new PayloadGeneration(1), first.generation());
            Assertions.assertEquals(new PayloadGeneration(2), second.generation());
            Assertions.assertEquals(first, registry.resolve(definition(1)));
            Assertions.assertEquals(second, registry.resolve(definition(2)));
            Assertions.assertEquals(1, count(database.connection(), "event_types"));
            Assertions.assertEquals(2, count(database.connection(), "payload_generations"));
        }
    }

    @Test
    void testResolveParticipatesInCallerTransaction(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("transaction.duckdb"))) {
            var connection = database.connection();
            DuckDbMigrations.migrate(connection);
            var registry = new DuckDbEventTypeRegistry(connection);

            execute(connection, "BEGIN TRANSACTION");
            var resolved = registry.resolve(definition(1));
            Assertions.assertTrue(registry.findEventType(resolved.eventType().id()).isPresent());
            execute(connection, "ROLLBACK");

            Assertions.assertTrue(registry.findEventType(EVENT_KEY).isEmpty());
            Assertions.assertTrue(registry.findPayloadGeneration(resolved.id()).isEmpty());
        }
    }

    @Test
    void testConcurrentResolutionIsSerializedWithinRegistryInstance(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("serialized.duckdb"))) {
            DuckDbMigrations.migrate(database.connection());
            var registry = new DuckDbEventTypeRegistry(database.connection());

            try (var executor = Executors.newFixedThreadPool(8)) {
                var futures = new ArrayList<java.util.concurrent.Future<
                    DuckDbEventTypeRegistry.PersistentPayloadGeneration
                >>();

                for (int index = 0; index < 32; index++) {
                    futures.add(executor.submit(() -> registry.resolve(definition(1))));
                }

                var expected = futures.getFirst().get();
                for (var future : futures) {
                    Assertions.assertEquals(expected, future.get());
                }
            }

            Assertions.assertEquals(1, count(database.connection(), "event_types"));
            Assertions.assertEquals(1, count(database.connection(), "payload_generations"));
        }
    }

    @Test
    void testDifferentKeysNeverOverwritePersistentIdentity(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("identity.duckdb"))) {
            DuckDbMigrations.migrate(database.connection());
            var registry = new DuckDbEventTypeRegistry(database.connection());

            var first = registry.resolve(definition(1));
            var otherKey = Key.key("example", "block_place");
            var second = registry.resolve(
                new EventTypeDefinition(otherKey, PayloadGeneration.FIRST)
            );

            Assertions.assertNotEquals(first.eventType().id(), second.eventType().id());
            Assertions.assertEquals(EVENT_KEY, registry.findEventType(first.eventType().id()).orElseThrow().key());
            Assertions.assertEquals(otherKey, registry.findEventType(second.eventType().id()).orElseThrow().key());
        }
    }

    private static EventTypeDefinition definition(int generation) {
        return new EventTypeDefinition(EVENT_KEY, new PayloadGeneration(generation));
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            Assertions.assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
