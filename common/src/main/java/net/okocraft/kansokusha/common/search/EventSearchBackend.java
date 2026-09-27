package net.okocraft.kansokusha.common.search;

import net.okocraft.kansokusha.api.KansokushaApi;
import net.okocraft.kansokusha.api.event.EventSubmission;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Internal submission and lookup surface for searchable communication events.
 *
 * <p>This is intentionally separate from the public {@link KansokushaApi}. Search text is a
 * derived projection used by Kansokusha's own search backend, not provider-defined event data.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public interface EventSearchBackend {

    boolean submitSearchable(EventSubmission submission, String searchText);

    /**
     * Executes a typed search asynchronously through the runtime's storage-owned read path.
     */
    CompletableFuture<SearchPage> search(SearchRequest request);

    /**
     * Looks up one persisted event by its internal UUID without exposing this operation publicly.
     */
    default CompletableFuture<Optional<EventDetail>> findEvent(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Returns historical values used for platform completion and event permission scoping.
     */
    default CompletableFuture<SearchMetadata> searchMetadata() {
        return CompletableFuture.completedFuture(SearchMetadata.empty());
    }

    CompletableFuture<List<UUID>> findEventIdsContaining(String literal);

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
