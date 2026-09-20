package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventTypeDefinition;
import net.okocraft.kansokusha.api.event.PayloadGeneration;
import org.jetbrains.annotations.NotNullByDefault;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

@NotNullByDefault
public final class DuckDbEventTypeRegistry {

    private final Connection connection;

    public DuckDbEventTypeRegistry(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public synchronized PersistentPayloadGeneration resolve(EventTypeDefinition definition) throws SQLException {
        Objects.requireNonNull(definition, "definition");

        var eventType = findEventType(definition.key()).orElse(null);
        if (eventType == null) {
            eventType = createEventType(definition.key());
        }

        var payloadGeneration = findPayloadGeneration(eventType, definition.payloadGeneration()).orElse(null);
        if (payloadGeneration == null) {
            payloadGeneration = createPayloadGeneration(eventType, definition.payloadGeneration());
        }

        return payloadGeneration;
    }

    public synchronized Optional<PersistentEventType> findEventType(Key key) throws SQLException {
        Objects.requireNonNull(key, "key");

        try (var statement = this.connection.prepareStatement(
            "SELECT id FROM event_types WHERE event_type_key = ?"
        )) {
            statement.setString(1, key.asString());

            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PersistentEventType(result.getInt("id"), key));
            }
        }
    }

    public synchronized Optional<PersistentEventType> findEventType(int id) throws SQLException {
        requirePositiveId(id);

        try (var statement = this.connection.prepareStatement(
            "SELECT event_type_key FROM event_types WHERE id = ?"
        )) {
            statement.setInt(1, id);

            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                    new PersistentEventType(id, Key.key(result.getString("event_type_key")))
                );
            }
        }
    }

    public synchronized Optional<PersistentPayloadGeneration> findPayloadGeneration(int id) throws SQLException {
        requirePositiveId(id);

        try (var statement = this.connection.prepareStatement(
            """
                SELECT
                    pg.generation,
                    et.id AS event_type_id,
                    et.event_type_key
                FROM payload_generations AS pg
                JOIN event_types AS et ON et.id = pg.event_type_id
                WHERE pg.id = ?
                """
        )) {
            statement.setInt(1, id);

            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }

                return Optional.of(
                    new PersistentPayloadGeneration(
                        id,
                        new PersistentEventType(
                            result.getInt("event_type_id"),
                            Key.key(result.getString("event_type_key"))
                        ),
                        new PayloadGeneration(result.getInt("generation"))
                    )
                );
            }
        }
    }

    private Optional<PersistentPayloadGeneration> findPayloadGeneration(
        PersistentEventType eventType,
        PayloadGeneration generation
    ) throws SQLException {
        try (var statement = this.connection.prepareStatement(
            """
                SELECT id
                FROM payload_generations
                WHERE event_type_id = ? AND generation = ?
                """
        )) {
            statement.setInt(1, eventType.id());
            statement.setInt(2, generation.value());

            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                    new PersistentPayloadGeneration(result.getInt("id"), eventType, generation)
                );
            }
        }
    }

    private PersistentEventType createEventType(Key key) throws SQLException {
        try (var statement = this.connection.prepareStatement(
            "INSERT INTO event_types (event_type_key) VALUES (?) RETURNING id"
        )) {
            statement.setString(1, key.asString());

            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Creating event type returned no identifier for " + key.asString());
                }
                return new PersistentEventType(result.getInt("id"), key);
            }
        }
    }

    private PersistentPayloadGeneration createPayloadGeneration(
        PersistentEventType eventType,
        PayloadGeneration generation
    ) throws SQLException {
        try (var statement = this.connection.prepareStatement(
            """
                INSERT INTO payload_generations (event_type_id, generation)
                VALUES (?, ?)
                RETURNING id
                """
        )) {
            statement.setInt(1, eventType.id());
            statement.setInt(2, generation.value());

            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException(
                        "Creating payload generation returned no identifier for "
                            + eventType.key().asString() + " generation " + generation.value()
                    );
                }
                return new PersistentPayloadGeneration(result.getInt("id"), eventType, generation);
            }
        }
    }

    private static void requirePositiveId(int id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
    }

    public record PersistentEventType(int id, Key key) {

        public PersistentEventType {
            requirePositiveId(id);
            Objects.requireNonNull(key, "key");
        }
    }

    public record PersistentPayloadGeneration(
        int id,
        PersistentEventType eventType,
        PayloadGeneration generation
    ) {

        public PersistentPayloadGeneration {
            requirePositiveId(id);
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(generation, "generation");
        }
    }
}
