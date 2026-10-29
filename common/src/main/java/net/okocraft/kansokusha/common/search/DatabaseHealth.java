package net.okocraft.kansokusha.common.search;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A snapshot of the state of the event database and of the write queue in front of it.
 */
@ApiStatus.Internal
@NotNullByDefault
public record DatabaseHealth(Stored stored, Queue queue) {

    public DatabaseHealth {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(queue, "queue");
    }

    /**
     * What the database file holds.
     *
     * @param events      the number of persisted events
     * @param playerNames the number of persisted player name records
     * @param oldestEvent when the oldest persisted event occurred, if any
     * @param newestEvent when the newest persisted event occurred, if any
     * @param databaseBytes the size of the database file
     * @param walBytes    the size of the write-ahead log that has not been checkpointed yet
     */
    public record Stored(
        long events,
        long playerNames,
        Optional<Instant> oldestEvent,
        Optional<Instant> newestEvent,
        long databaseBytes,
        long walBytes
    ) {

        public Stored {
            Objects.requireNonNull(oldestEvent, "oldestEvent");
            Objects.requireNonNull(newestEvent, "newestEvent");
        }
    }

    /**
     * What happened to the events submitted since the runtime started.
     *
     * @param queued   the number of events waiting to be written
     * @param capacity the maximum number of events that can wait
     * @param written  the number of events written to the database
     * @param lost     the number of events dropped because writing them failed
     * @param rejected the number of events refused because the queue was full
     */
    public record Queue(int queued, int capacity, long written, long lost, long rejected) {
    }
}
