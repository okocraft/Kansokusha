package net.okocraft.kansokusha.common.storage;

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

    Optional<UUID> resolvePlayerName(String name) throws SQLException;

    List<String> offlinePlayerNames() throws SQLException;

    List<UUID> findEventIdsContaining(String literal) throws SQLException;

    SearchPage search(SearchRequest request) throws SQLException;

    default SearchMetadata searchMetadata() throws SQLException {
        return SearchMetadata.empty();
    }

    int deleteExpired(Instant now) throws SQLException;

    void checkpoint() throws SQLException;

    StorageHealth health() throws SQLException;

    @Override
    void close() throws SQLException;
}
