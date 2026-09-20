package net.okocraft.kansokusha.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/**
 * Internal lifecycle bridge used by platform implementations to publish the API.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class KansokushaApiProvider {

    private KansokushaApiProvider() {
    }

    public static boolean publish(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        return Kansokusha.API.compareAndSet(Kansokusha.CLOSED_API, api);
    }

    public static boolean unpublish(KansokushaApi api) {
        Objects.requireNonNull(api, "api");
        return Kansokusha.API.compareAndSet(api, Kansokusha.CLOSED_API);
    }
}
