package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.search.DatabaseHealth;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import org.jetbrains.annotations.NotNullByDefault;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage backend used by the asynchronous runtime.
 *
 * <p>Implementations are owned by one storage thread and are not required to be thread-safe.</p>
 */
@NotNullByDefault
public interface Storage extends AutoCloseable {

    void append(List<QueuedEvent> events) throws SQLException;

    List<String> offlinePlayerNames() throws SQLException;

    Optional<EventDetail> findEvent(UUID eventId) throws SQLException;

    SearchPage search(SearchRequest request) throws SQLException;

    long count(SearchRequest request) throws SQLException;

    SearchMetadata searchMetadata() throws SQLException;

    /** Reads what the database holds. */
    default DatabaseHealth.Stored health() throws SQLException {
        throw new UnsupportedOperationException("Health is unavailable");
    }

    /**
     * Counts the persisted events by event type. This scans the events table, so it is meant to be
     * called off the storage thread: it reads through its own connection and does not block writes.
     */
    default Map<Key, Long> countEventsByType() throws SQLException {
        throw new UnsupportedOperationException("Event counts are unavailable");
    }

    int deleteExpired(Instant now) throws SQLException;

    @Override
    void close() throws SQLException;
}
