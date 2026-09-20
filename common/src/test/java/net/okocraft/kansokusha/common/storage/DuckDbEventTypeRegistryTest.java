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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class DuckDbEventTypeRegistryTest {

    private static final Key EVENT_KEY = Key.key("example", "block_break");

    @Test
    void testIdentitySurvivesReopenAndReregistration(@TempDir Path dir) throws Exception {
        var filepath = dir.resolve("registry.duckdb");

        DuckDbEventTypeRegistry.PersistentPayloadGeneration first;
        try (var database = DuckDbDatabase.open(filepath)) {
            DuckDbMigrations.migrate(database.connection());
            var registry = new DuckDbEventTypeRegistry(database);

            first = registry.resolve(definition(1));
            Assertions.assertEquals(EVENT_KEY, first.eventType().key());
            Assertions.assertEquals(new PayloadGeneration(1), first.generation());
        }

        try (var database = DuckDbDatabase.open(filepath)) {
            DuckDbMigrations.migrate(database.connection());
            var registry = new DuckDbEventTypeRegistry(database);

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
            var registry = new DuckDbEventTypeRegistry(database);

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
    void testResolveParticipatesInStorageOwnedTransaction(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("transaction.duckdb"))) {
            DuckDbMigrations.migrate(database.connection());
            var registry = new DuckDbEventTypeRegistry(database);

            Assertions.assertThrows(
                SQLException.class,
                () -> database.transaction(connection -> {
                    var resolved = registry.resolve(definition(1));
                    Assertions.assertTrue(registry.findEventType(resolved.eventType().id()).isPresent());
                    throw new SQLException("rollback");
                })
            );

            Assertions.assertTrue(registry.findEventType(EVENT_KEY).isEmpty());
            Assertions.assertEquals(0, count(database.connection(), "payload_generations"));
        }
    }

    @Test
    void testTransactionBlocksOtherRegistryAccessUntilRollback(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("transaction-serialization.duckdb"))) {
            DuckDbMigrations.migrate(database.connection());
            var firstRegistry = new DuckDbEventTypeRegistry(database);
            var secondRegistry = new DuckDbEventTypeRegistry(database);
            var firstResolved = new CountDownLatch(1);
            var allowRollback = new CountDownLatch(1);
            var otherKey = Key.key("example", "block_place");

            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> {
                    try {
                        database.transaction(connection -> {
                            firstRegistry.resolve(definition(1));
                            firstResolved.countDown();
                            await(allowRollback);
                            throw new SQLException("rollback");
                        });
                        throw new AssertionError("transaction should have rolled back");
                    } catch (SQLException expected) {
                        return null;
                    }
                });

                Assertions.assertTrue(firstResolved.await(5, TimeUnit.SECONDS));

                var second = executor.submit(
                    () -> secondRegistry.resolve(
                        new EventTypeDefinition(otherKey, PayloadGeneration.FIRST)
                    )
                );

                Assertions.assertThrows(
                    TimeoutException.class,
                    () -> second.get(200, TimeUnit.MILLISECONDS)
                );

                allowRollback.countDown();
                first.get(5, TimeUnit.SECONDS);
                var secondResolved = second.get(5, TimeUnit.SECONDS);

                Assertions.assertEquals(otherKey, secondResolved.eventType().key());
            }

            Assertions.assertTrue(firstRegistry.findEventType(EVENT_KEY).isEmpty());
            Assertions.assertTrue(secondRegistry.findEventType(otherKey).isPresent());
            Assertions.assertEquals(1, count(database.connection(), "event_types"));
            Assertions.assertEquals(1, count(database.connection(), "payload_generations"));
        }
    }

    @Test
    void testMultipleRegistryInstancesShareStorageSerializer(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("shared-serializer.duckdb"))) {
            DuckDbMigrations.migrate(database.connection());
            var firstRegistry = new DuckDbEventTypeRegistry(database);
            var secondRegistry = new DuckDbEventTypeRegistry(database);

            try (var executor = Executors.newFixedThreadPool(8)) {
                var futures = new ArrayList<java.util.concurrent.Future<
                    DuckDbEventTypeRegistry.PersistentPayloadGeneration
                >>();

                for (int index = 0; index < 32; index++) {
                    var registry = index % 2 == 0 ? firstRegistry : secondRegistry;
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
            var registry = new DuckDbEventTypeRegistry(database);

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

    private static void await(CountDownLatch latch) throws SQLException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new SQLException("Timed out waiting for test coordination.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for test coordination.", e);
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            Assertions.assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
