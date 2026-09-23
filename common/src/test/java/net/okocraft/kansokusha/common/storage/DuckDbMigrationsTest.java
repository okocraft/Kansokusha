package net.okocraft.kansokusha.common.storage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

class DuckDbMigrationsTest {

    @Test
    void testInitialSchemaMatchesV1Contract(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("schema.duckdb"))) {
            var connection = database.connection();
            DuckDbMigrations.migrate(database);

            Assertions.assertEquals("initial_v1_schema", migrationName(connection, 1));

            assertColumn(connection, "event_types", "id", "INTEGER", false);
            assertColumn(connection, "event_types", "event_type_key", "VARCHAR", false);
            assertColumn(connection, "payload_generations", "generation", "INTEGER", false);
            assertColumn(connection, "servers", "server_key", "VARCHAR", false);
            assertColumn(connection, "worlds", "world_key", "VARCHAR", false);
            assertColumn(connection, "retention_policies", "retention_policy_key", "VARCHAR", false);

            var eventColumns = tableColumns(connection, "events");
            Assertions.assertEquals(11, eventColumns.size());
            assertColumn(eventColumns, "payload_generation_id", "INTEGER", false);
            assertColumn(eventColumns, "occurred_at", "TIMESTAMP_MS", false);
            assertColumn(eventColumns, "server_id", "INTEGER", false);
            assertColumn(eventColumns, "world_id", "INTEGER", true);
            assertColumn(eventColumns, "block_x", "INTEGER", true);
            assertColumn(eventColumns, "block_y", "INTEGER", true);
            assertColumn(eventColumns, "block_z", "INTEGER", true);
            assertColumn(eventColumns, "subject_player_uuid", "UUID", true);
            assertColumn(eventColumns, "retention_policy_id", "INTEGER", false);
            assertColumn(eventColumns, "expires_at", "TIMESTAMP_MS", false);
            assertColumn(eventColumns, "payload", "BLOB", false);

            Assertions.assertEquals(0, eventIdentityConstraintCount(connection));
            Assertions.assertEquals(0, eventIndexCount(connection));
        }
    }

    @Test
    void testMetadataIdentityAndWorldScope(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("metadata.duckdb"))) {
            var connection = database.connection();
            DuckDbMigrations.migrate(database);

            execute(connection, "INSERT INTO event_types (event_type_key) VALUES ('example:event')");
            var eventTypeId = idForKey(connection, "event_types", "event_type_key", "example:event");
            Assertions.assertTrue(eventTypeId > 0);

            execute(
                connection,
                "INSERT INTO payload_generations (event_type_id, generation) VALUES (" + eventTypeId + ", 1)"
            );
            Assertions.assertTrue(singleInt(
                connection,
                "SELECT id FROM payload_generations WHERE event_type_id = " + eventTypeId + " AND generation = 1"
            ) > 0);

            execute(connection, "INSERT INTO servers (server_key) VALUES ('example:server_a'), ('example:server_b')");
            var serverA = idForKey(connection, "servers", "server_key", "example:server_a");
            var serverB = idForKey(connection, "servers", "server_key", "example:server_b");

            execute(
                connection,
                "INSERT INTO worlds (server_id, world_key) VALUES "
                    + "(" + serverA + ", 'minecraft:overworld'), "
                    + "(" + serverB + ", 'minecraft:overworld')"
            );
            Assertions.assertEquals(2, singleInt(
                connection,
                "SELECT count(*) FROM worlds WHERE world_key = 'minecraft:overworld'"
            ));

            Assertions.assertThrows(
                SQLException.class,
                () -> execute(
                    connection,
                    "INSERT INTO worlds (server_id, world_key) VALUES ("
                        + serverA + ", 'minecraft:overworld')"
                )
            );

            execute(
                connection,
                "INSERT INTO retention_policies (retention_policy_key) VALUES ('example:audit')"
            );
            Assertions.assertTrue(
                idForKey(connection, "retention_policies", "retention_policy_key", "example:audit") > 0
            );

            Assertions.assertThrows(
                SQLException.class,
                () -> execute(
                    connection,
                    "INSERT INTO payload_generations (event_type_id, generation) VALUES (999999, 1)"
                )
            );
        }
    }

    @Test
    void testMetadataConstraintsRejectInvalidRows(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("metadata-constraints.duckdb"))) {
            var connection = database.connection();
            DuckDbMigrations.migrate(database);

            execute(connection, "INSERT INTO event_types (event_type_key) VALUES ('example:event')");
            var eventTypeId = idForKey(connection, "event_types", "event_type_key", "example:event");
            execute(connection, "INSERT INTO servers (server_key) VALUES ('example:server')");
            var serverId = idForKey(connection, "servers", "server_key", "example:server");
            execute(
                connection,
                "INSERT INTO retention_policies (retention_policy_key) VALUES ('example:audit')"
            );

            assertInsertFails(
                connection,
                "INSERT INTO event_types (event_type_key) VALUES ('example:event')"
            );
            assertInsertFails(
                connection,
                "INSERT INTO servers (server_key) VALUES ('example:server')"
            );
            assertInsertFails(
                connection,
                "INSERT INTO retention_policies (retention_policy_key) VALUES ('example:audit')"
            );

            assertInsertFails(
                connection,
                "INSERT INTO event_types (id, event_type_key) VALUES (0, 'example:zero')"
            );
            assertInsertFails(
                connection,
                "INSERT INTO servers (id, server_key) VALUES (0, 'example:zero')"
            );
            assertInsertFails(
                connection,
                "INSERT INTO retention_policies (id, retention_policy_key) VALUES (0, 'example:zero')"
            );
            assertInsertFails(
                connection,
                "INSERT INTO payload_generations (id, event_type_id, generation) VALUES (0, "
                    + eventTypeId + ", 1)"
            );
            assertInsertFails(
                connection,
                "INSERT INTO worlds (id, server_id, world_key) VALUES (0, "
                    + serverId + ", 'example:zero')"
            );

            execute(
                connection,
                "INSERT INTO payload_generations (event_type_id, generation) VALUES (" + eventTypeId + ", 1)"
            );
            assertInsertFails(
                connection,
                "INSERT INTO payload_generations (event_type_id, generation) VALUES ("
                    + eventTypeId + ", 1)"
            );
            assertInsertFails(
                connection,
                "INSERT INTO payload_generations (event_type_id, generation) VALUES ("
                    + eventTypeId + ", 0)"
            );

            assertInsertFails(
                connection,
                "INSERT INTO worlds (server_id, world_key) VALUES (999999, 'minecraft:overworld')"
            );
        }
    }

    @Test
    void testEventOptionalFieldsAndLocationConstraint(@TempDir Path dir) throws Exception {
        try (var database = DuckDbDatabase.open(dir.resolve("events.duckdb"))) {
            var connection = database.connection();
            DuckDbMigrations.migrate(database);

            execute(connection, "INSERT INTO event_types (event_type_key) VALUES ('example:event')");
            var eventTypeId = idForKey(connection, "event_types", "event_type_key", "example:event");
            execute(
                connection,
                "INSERT INTO payload_generations (event_type_id, generation) VALUES (" + eventTypeId + ", 1)"
            );
            var generationId = singleInt(
                connection,
                "SELECT id FROM payload_generations WHERE event_type_id = " + eventTypeId
            );

            execute(connection, "INSERT INTO servers (server_key) VALUES ('example:server')");
            var serverId = idForKey(connection, "servers", "server_key", "example:server");

            execute(
                connection,
                "INSERT INTO retention_policies (retention_policy_key) VALUES ('example:audit')"
            );
            var retentionId = idForKey(
                connection,
                "retention_policies",
                "retention_policy_key",
                "example:audit"
            );

            try (var statement = connection.prepareStatement(
                """
                    INSERT INTO events (
                        payload_generation_id, occurred_at, server_id,
                        world_id, block_x, block_y, block_z, subject_player_uuid,
                        retention_policy_id, expires_at, payload
                    ) VALUES (
                        ?, make_timestamp_ms(?), ?,
                        NULL, NULL, NULL, NULL, NULL,
                        ?, make_timestamp_ms(?), ?
                    )
                    """
            )) {
                statement.setInt(1, generationId);
                statement.setLong(2, 1_700_000_000_123L);
                statement.setInt(3, serverId);
                statement.setInt(4, retentionId);
                statement.setLong(5, 1_700_086_400_123L);
                statement.setBytes(6, new byte[]{1, 2, 3});
                Assertions.assertEquals(1, statement.executeUpdate());
            }

            Assertions.assertEquals(1, singleInt(connection, "SELECT count(*) FROM events"));
            Assertions.assertNull(singleObject(connection, "SELECT world_id FROM events"));
            Assertions.assertNull(singleObject(connection, "SELECT subject_player_uuid FROM events"));

            execute(
                connection,
                "INSERT INTO worlds (server_id, world_key) VALUES ("
                    + serverId + ", 'minecraft:overworld')"
            );
            var worldId = singleInt(connection, "SELECT id FROM worlds");

            Assertions.assertThrows(
                SQLException.class,
                () -> execute(
                    connection,
                    """
                        INSERT INTO events (
                            payload_generation_id, occurred_at, server_id,
                            world_id, block_x, block_y, block_z,
                            retention_policy_id, expires_at, payload
                        ) VALUES (
                        """ + generationId + ", make_timestamp_ms(1700000000123), " + serverId + ", "
                        + worldId + ", 1, NULL, NULL, "
                        + retentionId + ", make_timestamp_ms(1700086400123), 'x'::BLOB)"
                )
            );
        }
    }

    private static Map<String, Column> tableColumns(Connection connection, String table) throws SQLException {
        var columns = new HashMap<String, Column>();

        try (var statement = connection.prepareStatement(
            """
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'main' AND table_name = ?
                """
        )) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    columns.put(
                        result.getString("column_name"),
                        new Column(
                            result.getString("data_type"),
                            "YES".equals(result.getString("is_nullable"))
                        )
                    );
                }
            }
        }

        return columns;
    }

    private static void assertColumn(
        Connection connection,
        String table,
        String column,
        String type,
        boolean nullable
    ) throws SQLException {
        assertColumn(tableColumns(connection, table), column, type, nullable);
    }

    private static void assertColumn(
        Map<String, Column> columns,
        String column,
        String type,
        boolean nullable
    ) {
        var actual = columns.get(column);
        Assertions.assertNotNull(actual, column);
        Assertions.assertEquals(type, actual.type(), column);
        Assertions.assertEquals(nullable, actual.nullable(), column);
    }

    private static int eventIdentityConstraintCount(Connection connection) throws SQLException {
        return singleInt(
            connection,
            """
                SELECT count(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'main'
                  AND table_name = 'events'
                  AND constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')
                """
        );
    }

    private static int eventIndexCount(Connection connection) throws SQLException {
        return singleInt(
            connection,
            "SELECT count(*) FROM duckdb_indexes() WHERE schema_name = 'main' AND table_name = 'events'"
        );
    }

    private static String migrationName(Connection connection, int version) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT name FROM schema_migrations WHERE version = ?"
        )) {
            statement.setInt(1, version);
            try (var result = statement.executeQuery()) {
                Assertions.assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static int idForKey(
        Connection connection,
        String table,
        String keyColumn,
        String key
    ) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT id FROM " + table + " WHERE " + keyColumn + " = ?"
        )) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                Assertions.assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static int singleInt(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            Assertions.assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static Object singleObject(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            Assertions.assertTrue(result.next());
            return result.getObject(1);
        }
    }

    private static void assertInsertFails(Connection connection, String sql) {
        Assertions.assertThrows(SQLException.class, () -> execute(connection, sql), sql);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record Column(String type, boolean nullable) {
    }
}
