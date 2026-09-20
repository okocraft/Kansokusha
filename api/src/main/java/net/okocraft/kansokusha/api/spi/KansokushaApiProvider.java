package net.okocraft.kansokusha.api.spi;

import net.okocraft.kansokusha.api.KansokushaApi;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Internal service-provider interface for locating the active Kansokusha API.
 */
@ApiStatus.Internal
@NotNullByDefault
public interface KansokushaApiProvider {

    /**
     * Returns the currently published API.
     *
     * @throws IllegalStateException if no API is currently published
     */
    KansokushaApi api();
}
