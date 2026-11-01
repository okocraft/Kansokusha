package net.okocraft.kansokusha.common.storage;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.common.search.DatabaseHealth;
import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.EventTypeStatistics;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * Summarizes the persisted events by event type. This scans the events table, so it is meant to be
     * called off the storage thread: it reads through its own connection and does not block writes.
     *
     * @param recentSince events that occurred at or after this instant are counted as recent
     */
    default Map<Key, EventTypeStatistics> eventStatisticsByType(Instant recentSince) throws SQLException {
        throw new UnsupportedOperationException("Event statistics are unavailable");
    }

    /**
     * Deletes the events that expired at or before {@code now}.
     *
     * @param archivedEventTypes event types that are left for {@link #archiveExpired} instead of being deleted
     */
    int deleteExpired(Instant now, Set<Key> archivedEventTypes) throws SQLException;

    /**
     * Writes the events of the given types that expired at or before {@code now} to a new zstd-compressed
     * Parquet file, then deletes them from the database.
     *
     * <p>The file is complete once it appears at {@code archiveFile}; an existing file is never replaced.
     * When the deletion fails after the file is written, the events stay in the database and are written
     * again by the next call, so readers should deduplicate archived events by {@code event_id}.</p>
     *
     * @return the number of archived events; no file is written when this is 0
     */
    default long archiveExpired(Instant now, Set<Key> eventTypes, Path archiveFile) throws SQLException, IOException {
        throw new UnsupportedOperationException("Archiving is unavailable");
    }

    /**
     * Recomputes {@code expires_at} of the events that occurred in {@code [from, to)} as
     * {@code occurred_at + retention}, so that a changed retention configuration also applies to stored events.
     *
     * @param retentionMillis        the retention period of each listed event type, in milliseconds
     * @param defaultRetentionMillis the retention period of the other event types, in milliseconds
     * @return the number of events whose expiry changed
     */
    default long recalculateExpiry(
        Instant from,
        Instant to,
        Map<Key, Long> retentionMillis,
        long defaultRetentionMillis
    ) throws SQLException {
        throw new UnsupportedOperationException("Recalculating expiry is unavailable");
    }

    @Override
    void close() throws SQLException;
}
