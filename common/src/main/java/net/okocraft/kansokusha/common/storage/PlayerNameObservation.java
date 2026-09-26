package net.okocraft.kansokusha.common.storage;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/**
 * Internal metadata attached to an accepted login event for updating the name projection.
 */
@NotNullByDefault
public record PlayerNameObservation(String username, long nameChangeExpiresAtMillis) {

    public PlayerNameObservation {
        Objects.requireNonNull(username, "username");
    }
}
