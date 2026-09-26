package net.okocraft.kansokusha.common.player;

import net.kyori.adventure.key.Key;
import net.okocraft.kansokusha.api.event.EventSubmission;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Internal player-name observation and lookup boundary.
 *
 * <p>This is intentionally separate from the public Kansokusha API. Login observations are
 * persisted through the normal event pipeline; the name history table is only a derived lookup
 * projection.</p>
 */
@ApiStatus.Internal
@NotNullByDefault
public interface PlayerNameDirectory {

    Key NAME_CHANGE_EVENT_TYPE = Key.key("kansokusha", "player_name_change");

    /**
     * Submits a login event together with the username observed by that login.
     *
     * <p>The login event and any derived name-change event are persisted atomically with the
     * lookup projection.</p>
     */
    boolean submitPlayerLogin(EventSubmission submission, String username);

    /**
     * Resolves a historical username case-insensitively to the UUID with the newest observation.
     */
    CompletableFuture<Optional<UUID>> resolvePlayerName(String name);

    /**
     * Returns case-insensitively de-duplicated historical usernames for offline completion.
     */
    CompletableFuture<List<String>> offlinePlayerNames();
}
