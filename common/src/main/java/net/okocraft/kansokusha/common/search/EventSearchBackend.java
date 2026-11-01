package net.okocraft.kansokusha.common.search;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.common.config.KansokushaConfig;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Internal submission and lookup surface used by Kansokusha's own listeners and commands.
 *
 * <p>This is intentionally separate from the public {@link KansokushaApi}. Search text and
 * player names are derived lookup data, not provider-defined event data.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public interface EventSearchBackend {

    /**
     * Submits a communication event together with its text searchable by {@code filter}.
     */
    boolean submitSearchable(EventSubmission submission, String searchText);

    /**
     * Submits a login event together with the username observed by that login.
     */
    boolean submitPlayerLogin(EventSubmission submission, String username);

    /**
     * Executes a typed search asynchronously through the runtime's storage-owned read path.
     */
    CompletableFuture<SearchPage> search(SearchRequest request);

    /** Counts all matches using the same visibility constraints, without cursor or page limit. */
    default CompletableFuture<Long> count(SearchRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Counting is unavailable"));
    }

    /** Reads the state of the database and of the write queue. */
    default CompletableFuture<DatabaseHealth> health() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Health is unavailable"));
    }

    /**
     * Summarizes the persisted events by event type without blocking writes. This scans every event,
     * so it can take a long time on a large database.
     */
    default CompletableFuture<Map<Key, EventTypeStatistics>> storedEventStatistics() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Event statistics are unavailable"));
    }

    /** The retention configuration the runtime was started with. */
    default KansokushaConfig.Retention retention() {
        throw new UnsupportedOperationException("Retention is unavailable");
    }

    /**
     * Recomputes the expiry of every stored event from {@link #retention()}. The work is split into
     * small chunks that interleave with writes, so it does not stall the write queue.
     *
     * @return the number of events whose expiry changed; fails with {@link RetentionAlreadyRunningException}
     * when an application is still running
     */
    default CompletableFuture<Long> applyRetention() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Applying retention is unavailable"));
    }

    CompletableFuture<Optional<EventDetail>> findEvent(UUID eventId);

    /**
     * Returns historical values used for platform completion and event permission scoping.
     */
    CompletableFuture<SearchMetadata> searchMetadata();

    /**
     * Returns case-insensitively de-duplicated observed usernames for completion.
     */
    CompletableFuture<List<String>> offlinePlayerNames();

    static EventSearchBackend require(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        if (api instanceof EventSearchBackend backend) {
            return backend;
        }
        throw new IllegalArgumentException(
            "KansokushaApi implementation does not provide the internal event search backend."
        );
    }
}
