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
