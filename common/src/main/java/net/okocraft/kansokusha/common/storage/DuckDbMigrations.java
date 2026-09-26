package net.okocraft.kansokusha.common.storage;

import java.sql.SQLException;

public final class DuckDbMigrations {

    static final DuckDbMigration INITIAL_V1_SCHEMA = DuckDbMigration.of(
        1,
        "initial_v1_schema",
        "CREATE SEQUENCE event_type_id_seq START WITH 1 MAXVALUE 2147483647 NO CYCLE",
        "CREATE SEQUENCE payload_generation_id_seq START WITH 1 MAXVALUE 2147483647 NO CYCLE",
        "CREATE SEQUENCE server_id_seq START WITH 1 MAXVALUE 2147483647 NO CYCLE",
        "CREATE SEQUENCE world_id_seq START WITH 1 MAXVALUE 2147483647 NO CYCLE",
        "CREATE SEQUENCE retention_policy_id_seq START WITH 1 MAXVALUE 2147483647 NO CYCLE",
        """
            CREATE TABLE event_types (
                id INTEGER PRIMARY KEY DEFAULT nextval('event_type_id_seq') CHECK (id > 0),
                event_type_key VARCHAR NOT NULL UNIQUE
            )
            """,
        """
            CREATE TABLE payload_generations (
                id INTEGER PRIMARY KEY DEFAULT nextval('payload_generation_id_seq') CHECK (id > 0),
                event_type_id INTEGER NOT NULL REFERENCES event_types(id),
                generation INTEGER NOT NULL CHECK (generation > 0),
                UNIQUE (event_type_id, generation)
            )
            """,
        """
            CREATE TABLE servers (
                id INTEGER PRIMARY KEY DEFAULT nextval('server_id_seq') CHECK (id > 0),
                server_key VARCHAR NOT NULL UNIQUE
            )
            """,
        """
            CREATE TABLE worlds (
                id INTEGER PRIMARY KEY DEFAULT nextval('world_id_seq') CHECK (id > 0),
                server_id INTEGER NOT NULL REFERENCES servers(id),
                world_key VARCHAR NOT NULL,
                UNIQUE (server_id, world_key)
            )
            """,
        """
            CREATE TABLE retention_policies (
                id INTEGER PRIMARY KEY DEFAULT nextval('retention_policy_id_seq') CHECK (id > 0),
                retention_policy_key VARCHAR NOT NULL UNIQUE
            )
            """,
        """
            CREATE TABLE events (
                payload_generation_id INTEGER NOT NULL,
                occurred_at TIMESTAMP_MS NOT NULL,
                server_id INTEGER NOT NULL,
                world_id INTEGER,
                block_x INTEGER,
                block_y INTEGER,
                block_z INTEGER,
                subject_player_uuid UUID,
                retention_policy_id INTEGER NOT NULL,
                expires_at TIMESTAMP_MS NOT NULL,
                payload BLOB NOT NULL,
                CHECK (
                    (block_x IS NULL AND block_y IS NULL AND block_z IS NULL)
                    OR (
                        world_id IS NOT NULL
                        AND block_x IS NOT NULL
                        AND block_y IS NOT NULL
                        AND block_z IS NOT NULL
                    )
                )
            )
            """
    );

    static final DuckDbMigration OPTIONAL_EVENT_SERVER = DuckDbMigration.of(
        2,
        "optional_event_server",
        "ALTER TABLE events ALTER COLUMN server_id DROP NOT NULL"
    );

    private static final DuckDbMigrationRunner RUNNER = DuckDbMigrationRunner.of(
        INITIAL_V1_SCHEMA,
        OPTIONAL_EVENT_SERVER
    );

    private DuckDbMigrations() {
    }

    public static void migrate(DuckDbDatabase database) throws SQLException {
        database.serialized(connection -> {
            RUNNER.migrate(connection);
            return null;
        });
    }
}
