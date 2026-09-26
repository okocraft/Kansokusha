package net.okocraft.kansokusha.common.storage;

import org.jetbrains.annotations.NotNullByDefault;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Storage backend used by the asynchronous runtime.
 *
 * <p>Implementations are owned by one storage thread and are not required to be thread-safe.</p>
 */
@NotNullByDefault
public interface Storage extends AutoCloseable {

    void append(List<QueuedEvent> events) throws SQLException;

    int deleteExpired(Instant now) throws SQLException;

    void checkpoint() throws SQLException;

    StorageHealth health() throws SQLException;

    @Override
    void close() throws SQLException;
}
