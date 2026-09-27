package net.okocraft.kansokusha.common.storage;

import net.okocraft.kansokusha.common.search.EventDetail;
import net.okocraft.kansokusha.common.search.SearchMetadata;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import org.jetbrains.annotations.NotNullByDefault;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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

    SearchMetadata searchMetadata() throws SQLException;

    int deleteExpired(Instant now) throws SQLException;

    @Override
    void close() throws SQLException;
}
